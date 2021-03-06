package com.omegaup.grader.drivers

import com.omegaup._
import com.omegaup.data._
import com.omegaup.grader._
import com.omegaup.libinteractive.idl.IDL
import com.omegaup.libinteractive.idl.Parser
import java.io._
import java.util.concurrent._
import java.util.zip.DeflaterOutputStream
import scala.util.matching.Regex
import scala.collection.mutable.{ListBuffer, TreeSet}
import Language._
import Verdict._
import Status._
import Validator._

object OmegaUpDriver extends Driver with Log with Using {
  def getInputEntries(treeHashes: Iterable[(String, String)], alias:
    String)(implicit ctx: Context):
  Iterable[InputEntry] = {
    val path = new File(ctx.config.common.roots.problems, alias)
    val objectsPath = new File(path, ".git/objects")
    val casesPath = new File(path, "cases/in")

    // Return a lazy view with the hashes plus their stream.
    treeHashes.view.map { case (name: String, hash: String) => {
        val file = new File(objectsPath, hash.substring(0, 2) + "/" + hash.substring(2))
        if (file.exists) {
          new InputEntry(name, new FileInputStream(file), file.length, hash)
        } else {
          // The file is likely within a .pack file. We need to compress it manually.
          // TODO(lhchavez): It's probably not a great idea to do this in-memory.
          val originalFile = new File(casesPath, name)
          val header = s"blob ${originalFile.length}\u0000".getBytes
          val bytes = new ByteArrayOutputStream
          using (new DeflaterOutputStream(bytes)) { zlib => {
            zlib.write(header)
            using (new FileInputStream(originalFile)) {
              FileUtil.copy(_, zlib)
            }
          }}

          new InputEntry(name, new ByteArrayInputStream(bytes.toByteArray), bytes.size, null)
        }
    }}
  }

  override def run(run: Run)(implicit ctx: RunContext): Run = {
    val alias = run.problem.alias
    val lang = run.language
    val gradeDirectory = new File(ctx.config.common.roots.grade,
      run.guid.substring(0, 2) + "/" + run.guid.substring(2))

    log.info("Compiling {} {} on {}", alias, run.id, ctx.service.name)

    if (gradeDirectory.exists) {
      FileUtil.deleteDirectory(gradeDirectory)
    }
    gradeDirectory.mkdirs

    run.status = Status.Compiling
    run.judged_by = Some(ctx.service.name)
    ctx.updateVerdict(run)

    val code = FileUtil.read(
      ctx.config.common.roots.submissions + "/" +
      run.guid.substring(0, 2) + "/" + run.guid.substring(2))
    val compileMessage = createCompileMessage(run, code)
    val output = ctx.trace(EventCategory.Compile) {
      ctx.service.compile(compileMessage)
    }

    if(output.status != "ok") {
      FileUtil.write(
        new File(gradeDirectory, "compile_error.log"),
        output.error.get
      )

      run.status = Status.Ready
      run.verdict = Verdict.CompileError
      run.memory = 0
      run.runtime = 0
      run.score = 0

      return run
    }

    val git = new Git(new File(ctx.config.common.roots.problems, alias))
    val input = git.getTreeHash("cases/in")
    val msg = new RunInputMessage(
      output.token.get,
      debug = ctx.debug,
      timeLimit = run.problem.time_limit match {
        case Some(x) => x
        case _ => 1000
      },
      validatorTimeLimit = run.problem.validator_time_limit match {
        case Some(x) => x
        case _ => 1000
      },
      overallWallTimeLimit = run.problem.overall_wall_time_limit match {
        case Some(x) => x
        case _ => 60000
      },
      extraWallTime = run.problem.extra_wall_time,
      memoryLimit = run.problem.memory_limit match {
        case Some(x) => x.toInt
        case _ => 65535
      },
      outputLimit = run.problem.output_limit match {
        case Some(x) => x.toLong
        case _ => 10240
      },
      stackLimit = run.problem.stack_limit match {
        case Some(x) => x.toLong
        case _ => 10485760
      },
      input = Some(input),
      interactive = compileMessage.interactive match {
        case None => None
        case Some(interactive) => {
          val parser = new Parser
          val idl = parser.parse(interactive.idlSource)

          Some(InteractiveRuntimeDescription(
            main = idl.main.name,
            interfaces = idl.interfaces.map(_.name),
            parentLang = interactive.parentLang
          ))
        }
      }
    )

    run.status = Status.Running
    ctx.updateVerdict(run)

    val target = new File(gradeDirectory, "results")
    target.mkdirs
    val placer = new CasePlacer(target)

    log.info("Running {}({}) on {}", alias, run.id, ctx.service.name)
    var response = ctx.trace(EventCategory.Run) {
      ctx.service.run(msg, placer)
    }
    log.debug("Ran {} {}, returned {}", alias, run.id, response)
    if (response.status != "ok") {
      if (response.error.get ==  "missing input") {
        log.info("Received a missing input message, trying to send input from {} ({})", alias, ctx.service.name)
        ctx.trace(EventCategory.Input) {
          if(ctx.service.input(
            input, getInputEntries(git.getTreeEntries(input), alias)
          ).status != "ok") {
            throw new RuntimeException("Unable to send input. giving up.")
          }
        }
        response = ctx.trace(EventCategory.Run) {
          ctx.service.run(msg, placer)
        }
        if (response.status != "ok") {
          log.error("Second try, ran {}({}) on {}, returned {}", alias, run.id, ctx.service.name, response)
          throw new RuntimeException("Unable to run submission after sending input. giving up.")
        }
      } else {
        throw new RuntimeException(response.error.get)
      }
    }

    // Finally return the run.
    run
  }

  class CasePlacer(directory: File)(implicit ctx: Context) extends Object with RunCaseCallback with Using with Log {
    def apply(filename: String, length: Long, stream: InputStream): Unit = {
      log.debug("Placing file {}({}) into {}", filename, length, directory)
      val target = new File(directory, filename)
      if (!target.getParentFile.exists) {
        target.getParentFile.mkdirs
      }
      using (new FileOutputStream(new File(directory, filename))) {
        FileUtil.copy(stream, _)
      }
    }
  }

  override def validateOutput(run: Run)(implicit ctx: RunContext): Run = {
    ctx.trace(EventCategory.Validate) {
      run.problem.validator match {
        case Validator.Custom => CustomValidator.validateRun(run)
        case Validator.Literal => LiteralValidator.validateRun(run)
        case Validator.Token => TokenValidator.validateRun(run)
        case Validator.TokenCaseless => TokenCaselessValidator.validateRun(run)
        case Validator.TokenNumeric => TokenNumericValidator.validateRun(run)
        case Validator.TokenAbsoluteNumeric => TokenAbsoluteNumericValidator.validateRun(run)
        case _ => throw new IllegalArgumentException("Validator " + run.problem.validator + " not found")
      }
    }
  }

  override def cleanResults(run: Run)(implicit ctx: RunContext): Unit = {
    val gradeDirectory = new File(ctx.config.common.roots.grade,
      run.guid.substring(0, 2) + "/" + run.guid.substring(2))
		val resultsDirectory = new File(gradeDirectory, "results")
		FileUtil.zipDirectory(resultsDirectory, new File(gradeDirectory, "results.zip"))
		FileUtil.deleteDirectory(resultsDirectory)
  }

  @throws(classOf[FileNotFoundException])
  private def createCompileMessage(run: Run, code: String)(implicit ctx: RunContext):
      CompileInputMessage = {
    var validatorLang: Option[String] = None
    var validatorCode: Option[List[(String, String)]] = None

    if (run.problem.validator == Validator.Custom) {
      List("c", "cpp", "py", "pas", "rb", "hs", "java").map(lang => {
        (lang -> new File(
          ctx.config.common.roots.problems,
          run.problem.alias + "/validator." + lang)
        )
      }).find(_._2.exists) match {
        case Some((lang, validator)) => {
          log.debug("Using custom validator {} for problem {}",
                validator.getCanonicalPath,
                run.problem.alias)
          validatorLang = Some(lang)
          validatorCode = Some(List(("Main." + lang, FileUtil.read(validator))))
        }

        case _ => {
          throw new FileNotFoundException("Validator for problem " + run.problem.alias +
                                          " was set to 'custom', but no validator program" +
                                          " was found.")
        }
      }
    } else {
      log.debug("Using {} validator for problem {}", run.problem.validator, run.problem.alias)
    }

    val codes = new ListBuffer[(String,String)]
    val interactiveRoot = new File(
      ctx.config.common.roots.problems,
      run.problem.alias + "/interactive"
    )
    var interactive: Option[InteractiveDescription] = None

    if (interactiveRoot.isDirectory) {
      log.debug("Using interactive mode problem {}", run.problem.alias)

      val interactiveFiles = interactiveRoot
        .list
        .map(new File(interactiveRoot, _))
        .filter(_.isFile)

      val idlFile = interactiveFiles.find(_.getName.endsWith(".idl"))

      if (!idlFile.isEmpty) {
        val idlSource = FileUtil.read(idlFile.get)
        val parser = new Parser
        val parsedIdl = parser.parse(idlSource)
        val mainFile = interactiveFiles.find(file => {
          file.getName.startsWith(parsedIdl.main.name + ".") &&
          !file.getName.contains(".distrib.")
        })

        if (!mainFile.isEmpty) {
          interactive = Some(InteractiveDescription(
            FileUtil.read(idlFile.get),
            parentLang = FileUtil.extension(mainFile.get),
            childLang = run.language.toString,
            moduleName = FileUtil.removeExtension(idlFile.get)
          ))

          codes += s"${interactive.get.moduleName}.${run.language.toString}" -> code
          codes += mainFile.get.getName -> FileUtil.read(mainFile.get)
        } else {
          throw new FileNotFoundException(
            new File(interactiveRoot, parsedIdl.main.name + ".*").getCanonicalPath)
        }
      } else {
        val unitNameFile = new File(interactiveRoot, "unitname")
        if (!unitNameFile.isFile) {
          throw new FileNotFoundException(unitNameFile.getCanonicalPath)
        }

        val langDir = new File(interactiveRoot, run.language.toString)
        if (!langDir.isDirectory) {
          throw new FileNotFoundException(langDir.getCanonicalPath)
        }

        langDir
          .list
          .map(new File(langDir, _))
          .filter(_.isFile)
          .foreach(file => { codes += file.getName -> FileUtil.read(file) })

        val unitName = FileUtil.read(unitNameFile)
        codes += unitName + "." + run.language.toString -> code

        if (codes.size < 2) {
          throw new FileNotFoundException(langDir.getCanonicalPath)
        }
      }
    } else {
      codes += "Main." + run.language.toString -> code
    }

    new CompileInputMessage(run.language.toString,
                            codes.result,
                            validatorLang,
                            validatorCode,
                            interactive,
                            ctx.debug)
  }

  def setLogs(run: Run, logs: String)(implicit ctx: RunContext): Unit = {
    val id = run.id
    val gradeDirectory = new File(ctx.config.common.roots.grade,
      run.guid.substring(0, 2) + "/" + run.guid.substring(2))
    val logFile = new File(gradeDirectory, "run.log")
    FileUtil.write(logFile, logs)
  }
}
