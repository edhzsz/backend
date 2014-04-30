package omegaup.grader

import java.util.concurrent._
import java.text.ParseException
import omegaup._
import omegaup.data._
import omegaup.grader.drivers._
import omegaup.runner._
import scala.util.parsing.combinator.syntactical._
import scala.collection.immutable.{HashMap, HashSet}

trait RunRouter {
	def apply(run: Run): String
}

object RoutingDescription extends StandardTokenParsers {
	val defaultQueueName = "#default"
	lexical.delimiters ++= List("(", ")", "[", "]", "{", "}", ",", ":", "==", "||", "&&")
	lexical.reserved += ("in", "runners", "condition", "contest", "user", "name", "slow")

	def parse(input: String): (Map[String,String], RunRouter) = routingTable(new lexical.Scanner(input)) match {
		case Success(table, _) => table
		case NoSuccess(msg, input) => {
			System.err.println(input.pos.longString)
			throw new ParseException(msg, input.offset)
		}
	}

	private def routingTable = phrase(rep(queue)) ^^ { (list: List[(String, List[String], RunMatcher)]) => (
		new HashMap[String,String] ++ list.map { case (name, runners, condition) =>
			runners.map(_ -> name)
		}.flatten,
		new RunRouterImpl(list.map { case (name, runners, condition) => condition -> name })
	)}
	private def queue = "{" ~ name ~ "," ~ runners ~ "," ~ condition ~ "}" ^^ { case "{" ~ name ~ "," ~ runners ~ "," ~ condition ~ "}" => (name, runners, condition) }
	private def name = "name" ~ ":" ~> stringLit
	private def runners = "runners" ~ ":" ~> stringList
	private def condition = "condition" ~ ":" ~> expr
	private def expr: Parser[RunMatcher] = ( "(" ~> expr <~ ")" ) | orExpr
	private def orExpr: Parser[RunMatcher] = rep1sep(andExpr, "||") ^^ { (andList: List[RunMatcher]) =>
		andList.length match {
			case 1 => andList(0)
			case _ => new OrMatcher(andList)
		}
	}
	private def andExpr: Parser[RunMatcher] = rep1sep(opExpr, "&&") ^^ { (opList: List[RunMatcher]) =>
		opList.length match {
			case 1 => opList(0)
			case _ => new AndMatcher(opList)
		}
	}
	private def opExpr: Parser[RunMatcher] = eqExpr | inExpr | slowExpr
	private def eqExpr: Parser[RunMatcher] = param ~ "==" ~ stringLit ^^ { case param ~ "==" ~ arg => new EqMatcher(param, arg) }
	private def inExpr: Parser[RunMatcher] = param ~ "in" ~ stringList ^^ { case param ~ "in" ~ arg => new InMatcher(param, arg) }
	private def slowExpr: Parser[RunMatcher] = "slow" ^^ { case "slow" => new SlowMatcher() }

	private def param: Parser[String] = "contest" | "user"
	private def stringList: Parser[List[String]] = "[" ~> rep1sep(stringLit, ",") <~ "]"

	private class RunRouterImpl(routingMap: List[(RunMatcher, String)]) extends Object with RunRouter {
		def apply(run: Run): String = {
			for (entry <- routingMap) {
				if (entry._1(run)) return entry._2
			}
			defaultQueueName
		}

		override def toString(): String = routingMap.toString
	}

	private trait RunMatcher {
		def apply(run: Run): Boolean
		def getParam(run: Run, param: String) = param match {
			case "contest" => {
				run.contest match {
					case None => ""
					case Some(contest) => contest.alias
				}
			}
			case "user" => run.user.username
		}
	}

	private class EqMatcher(param: String, arg: String) extends Object with RunMatcher {
		def apply(run: Run): Boolean = getParam(run, param) == arg
		override def toString(): String = param + " == " + arg
	}

	private class InMatcher(param: String, arg: List[String]) extends Object with RunMatcher {
		private val set = new HashSet[String]() ++ arg
		def apply(run: Run): Boolean = set.contains(getParam(run, param))
		override def toString(): String = param + " in " + "[" + set.mkString(", ") + "]"
	}

	private class SlowMatcher() extends Object with RunMatcher {
		def apply(run: Run): Boolean = run.problem.slow
	}

	private class OrMatcher(arg: List[RunMatcher]) extends Object with RunMatcher {
		def apply(run: Run): Boolean = arg.exists(_(run))
		override def toString(): String = "(" + arg.mkString(" || ") + ")"
	}

	private class AndMatcher(arg: List[RunMatcher]) extends Object with RunMatcher {
		def apply(run: Run): Boolean = arg.forall(_(run))
		override def toString(): String = arg.mkString(" && ")
	}
}

class RunnerRouter(dispatcherNames: Map[String,String], runRouter: RunRouter) extends ServiceInterface with Log {
	private val dispatchers = new HashMap[String, RunnerDispatcher] ++
	(new HashSet[String]() ++ dispatcherNames.map(_._2) + RoutingDescription.defaultQueueName).map { (queue: String) => {
		queue -> new RunnerDispatcher(queue)
	}}

	def status() = dispatchers.toMap.map(entry => {entry._1 -> entry._2.status()})

	def register(hostname: String, port: Int): RegisterOutputMessage = {
		dispatch(hostname).register(hostname, port)
	}

	def deregister(hostname: String, port: Int): RegisterOutputMessage = {
		dispatch(hostname).deregister(hostname, port)
	}

	def addRunner(runner: RunnerService) = {
		dispatch(runner.name).addRunner(runner)
	}

	def addRun(run: Run) = {
		dispatchers(runRouter(run)).addRun(run)
	}

	override def stop(): Unit = {
		dispatchers foreach (_._2.stop)
	}

	override def join(): Unit = {
		dispatchers foreach (_._2.join)
	}

	private def dispatch(hostname: String): RunnerDispatcher = {
		if (dispatcherNames.contains(hostname)) dispatchers(dispatcherNames(hostname))
			dispatchers(RoutingDescription.defaultQueueName)
	}
}

class RunnerDispatcher(val name: String) extends ServiceInterface with Log {
	private case class InFlightRun(service: RunnerService, run: Run, timestamp: Long)
	private val registeredEndpoints = scala.collection.mutable.HashMap.empty[RunnerEndpoint, Long]
	private val runnerQueue = scala.collection.mutable.Queue.empty[RunnerService]
	private val runQueue = scala.collection.mutable.Queue.empty[Run]
	private val runsInFlight = scala.collection.mutable.HashMap.empty[Long, InFlightRun]
	private val executor = Executors.newCachedThreadPool
	private var flightIndex: Long = 0
	private val lock = new Object

	private val pruner = new java.util.Timer("Flight pruner", true)
	pruner.scheduleAtFixedRate(
		new java.util.TimerTask() {
			override def run(): Unit = pruneFlights
		},
		Config.get("grader.flight_pruner.interval", 60) * 1000,
		Config.get("grader.flight_pruner.interval", 60) * 1000
	)

	def status() = lock.synchronized {
		QueueStatus(
			run_queue_length = runQueue.size,
			runner_queue_length = runnerQueue.size,
			runners = registeredEndpoints.keys.map(_.hostname).toList,
			running = runsInFlight.values.map(ifr => new Running(ifr.service.name, ifr.run.id.toInt)).toList
		)
	}

	def register(hostname: String, port: Int): RegisterOutputMessage = {
		val endpoint = new RunnerEndpoint(hostname, port)

		lock.synchronized {
			if (!registeredEndpoints.contains(endpoint)) {
				val proxy = new RunnerProxy(endpoint.hostname, endpoint.port) 
				info("Registering {}", proxy)
				registeredEndpoints += endpoint -> System.currentTimeMillis
				addRunner(proxy)
			}
			registeredEndpoints(endpoint) = System.currentTimeMillis
		}

		info("Runner queue length {} known endpoints {}", runnerQueue.size, registeredEndpoints.size)

		new RegisterOutputMessage()
	}

	private def deregisterLocked(endpoint: RunnerEndpoint) = {
		if (registeredEndpoints.contains(endpoint)) {
			info("De-registering {}", endpoint)
			registeredEndpoints -= endpoint
		}
	}

	def deregister(hostname: String, port: Int): RegisterOutputMessage = {
		val endpoint = new RunnerEndpoint(hostname, port)

		lock.synchronized {
			deregisterLocked(endpoint)
		}

		info("Runner queue length {} known endpoints {}", runnerQueue.size, registeredEndpoints.size)

		new RegisterOutputMessage()
	}

	def addRun(run: Run) = {
		lock.synchronized {
			debug("Adding run {}", run)
			runQueue += run
			dispatchLocked
		}
	}

	def addRunner(runner: RunnerService) = {
		lock.synchronized {
			addRunnerLocked(runner)
		}
	}

	private class RunnerEndpoint(val hostname: String, val port: Int) {
		def ==(o: RunnerEndpoint) = hostname == o.hostname && port == o.port
		override def hashCode() = 28227 + 97 * hostname.hashCode + port
		override def equals(other: Any) = other match {
			case x:RunnerEndpoint => hostname == x.hostname && port == x.port
			case _ => false
		}
		override def toString() = "RunnerEndpoint(%s:%d)".format(hostname, port)
	}

	private class GradeTask(
		dispatcher: RunnerDispatcher,
		flightIndex: Long,
		var r: Run,
		runner: RunnerService,
		driver: Driver
	) extends Runnable {
		override def run(): Unit = {
			val future = dispatcher.executor.submit(new Callable[Run]() {
					override def call(): Run = {
						driver.run(r.copy, runner)
					}
			})

			r = try {
				future.get(Config.get("grader.runner.timeout", 10 * 60) * 1000, TimeUnit.MILLISECONDS)
			} catch {
				case e: ExecutionException => {
					error("Submission {} {} failed - {} {}",
						r.problem.alias,
						r.id,
						e.getCause.toString,
						e.getCause.getStackTrace
					)

					e.getCause match {
						case inner: java.net.SocketException => {
							// Probably a network error of some sort. No use in re-queueing the runner.
							runner match {
								case proxy: omegaup.runner.RunnerProxy => dispatcher.deregister(proxy.hostname, proxy.port)
								case _ => {}
							}

							// But do re-queue the run
							dispatcher.addRun(r)

							// And commit suicide
							throw e
						}
						case _ => {
							r.score = 0
							r.contest_score = 0
							r.status = Status.Ready
							r.veredict = Veredict.JudgeError
						}
					}

					r
				}
				case e: TimeoutException => {
					error("Submission {} {} timed out - {} {}", r.problem.alias, r.id, e.toString, e.getStackTrace)

					// Probably a network error of some sort. No use in re-queueing the runner.
					runner match {
						case proxy: omegaup.runner.RunnerProxy => dispatcher.deregister(proxy.hostname, proxy.port)
						case _ => {}
					}

					r
				}
			} finally {
				dispatcher.flightFinished(flightIndex)
			}

			Manager.updateVeredict(
				if (r.status == Status.Ready) {
					r
				} else {
					try {
						driver.grade(r.copy)
					} catch {
						case e: Exception => {
							r.score = 0
							r.contest_score = 0
							r.status = Status.Ready
							r.veredict = Veredict.JudgeError

							r
						}
					}
				}
			)
		}
	}

	private def runLocked() = {
		val runner = runnerQueue.dequeue
		val run = runQueue.dequeue

		runsInFlight += flightIndex -> InFlightRun(runner, run, System.currentTimeMillis)
		executor.submit(new GradeTask(this, flightIndex, run, runner, OmegaUpDriver))

		flightIndex += 1
	}

	private def pruneFlights() = lock.synchronized {
		var cutoffTime = System.currentTimeMillis - Config.get("grader.runner.timeout", 10 * 60) * 1000
		runsInFlight.foreach { case (i, flightInfo) => {
			if (flightInfo.timestamp < cutoffTime) {
				warn("Expiring stale flight {}, run {}", flightInfo.service, flightInfo.run.id)
				flightInfo.service match {
					case proxy: RunnerProxy => deregisterLocked(new RunnerEndpoint(proxy.hostname, proxy.port))
					case _ => {}
				}
				runsInFlight -= i
			}
		}}
	}

	private def flightFinished(flightIndex: Long) = lock.synchronized {
		if (runsInFlight.contains(flightIndex)) {
			var flightRun = runsInFlight(flightIndex)
			runsInFlight -= flightIndex
			addRunnerLocked(flightRun.service)
		} else {
			error("Lost track of flight {}!", flightIndex)
			throw new RuntimeException("Flight corrupted, bail out")
		}
	}

	private def addRunnerLocked(runner: RunnerService) = {
		debug("Adding runner {}", runner)
		runnerQueue += runner
		dispatchLocked
	}

	private def dispatchLocked() = {
		// Prune any runners that are not registered or haven't communicated in a while.
		debug("Before pruning the queue {}", status)
		var cutoffTime = System.currentTimeMillis -
			Config.get("grader.runner.queue_timeout", 10 * 60 * 1000)
		runnerQueue.dequeueAll (
			_ match {
				case proxy: omegaup.runner.RunnerProxy => {
					val endpoint = new RunnerEndpoint(proxy.hostname, proxy.port)
					// Also expire stale endpoints.
					if (registeredEndpoints.contains(endpoint) &&
							registeredEndpoints(endpoint) < cutoffTime) {
						warn("Stale endpoint {}", proxy)
						deregister(endpoint.hostname, endpoint.port)
					}
					!registeredEndpoints.contains(endpoint)
				}
				case _ => false
			}
		)

		debug("After pruning the queue {}", status)

		if (!runnerQueue.isEmpty && !runQueue.isEmpty) {
			debug("But there's enough to run something!")
			runLocked
		}
	}

	override def stop(): Unit = {
		executor.shutdown
	}

	override def join(): Unit = {
		executor.awaitTermination(Long.MaxValue, TimeUnit.NANOSECONDS)
	}
}

/* vim: set noexpandtab: */
