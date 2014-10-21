import java.io._

import com.omegaup._
import com.omegaup.data._
import com.omegaup.runner._
import com.omegaup.libinteractive.idl.Parser
import com.omegaup.libinteractive.target.Options
import com.omegaup.libinteractive.target.Command
import org.slf4j._

import org.scalatest.{FlatSpec, BeforeAndAfterAll}
import org.scalatest.Matchers

object NullRunCaseCallback extends Object with RunCaseCallback {
  def apply(filename: String, length: Long, stream: InputStream): Unit = {}
}

class CompileSpec extends FlatSpec with Matchers with BeforeAndAfterAll {
  override def beforeAll() {
    import java.util.zip._

    val root = new File("test-env")

    if (root.exists()) {
      FileUtil.deleteDirectory(root.getCanonicalPath)
    }

    root.mkdir()
    new File(root.getCanonicalPath + "/compile").mkdir()

    Config.set("runner.preserve", true)
    Config.set("compile.root", root.getCanonicalPath + "/compile")
    Config.set("runner.sandbox.path", new File("../sandbox").getCanonicalPath)
    Config.set("runner.minijail.path", new File("/var/lib/minijail").getCanonicalPath)
    Config.set("logging.level", "debug")

    Logging.init()
  }

  "Compile error" should "be correctly handled" in {
    val runner = new Runner("test", Minijail)

    val test1 = runner.compile(CompileInputMessage("c", List(("Main.c", """
      foo
    """))))
    test1.status should equal ("compile error")
    
    val test2 = runner.compile(CompileInputMessage("c", List(("Main.c", """
      #include<stdio.h>
    """))))
    test2.status should equal ("compile error")
    
    val test3 = runner.compile(CompileInputMessage("c", List(("Main.c", """
      #include</dev/random>
    """))))
    test3.status should equal ("compile error")
    
    val test4 = runner.compile(CompileInputMessage("cpp", List(("Main.cpp", """
      foo
    """))))
    test4.status should equal ("compile error")
    
    val test5 = runner.compile(CompileInputMessage("cpp", List(("Main.cpp", """
      #include<stdio.h>
    """))))
    test5.status should equal ("compile error")
    
    val test6 = runner.compile(CompileInputMessage("cpp", List(("Main.cpp", """
      #include</dev/random>
    """))))
    test6.status should equal ("compile error")
    
    val test7 = runner.compile(CompileInputMessage("java", List(("Main.java", """
      foo
    """))))
    test7.status should equal ("compile error")
    test7.error should not equal (Some("Class should be called \"Main\"."))
    
    val test8 = runner.compile(CompileInputMessage("java", List(("Main.java", """
      class Foo {
        public static void main(String[] args) {
          System.out.println("Hello, World!\n");
        }
      }
    """))))
    test8.status should equal ("compile error")
    test8.error should equal (Some("Class should be called \"Main\"."))
  }
  
  "OK" should "be correctly handled" in {
    val runner = new Runner("test", Minijail)

    val zipRoot = new File("test-env")

    val test1 = runner.compile(CompileInputMessage("c", List(("Main.c", """
      #include<stdio.h>
      #include<stdlib.h>
      int main() {
        int x;
        (void)scanf("%d", &x);
        switch (x) {
          case 0:
            printf("Hello, World!\n");
            break;
          case 1:
            while(1);
            break;
          case 2:
            fork();
            break;
          case 3:
            while(1) (void)malloc(1024*1024);
            break;
          case 4:
            while(1) printf("trololololo\n");
            break;
          case 5:
            (void)fopen("/etc/shadow", "r");
            break;
          case 6:
            printf("%d", *(int*)(x-6));
            break;
          case 7:
            printf("%d", 1/(x-7));
            break;
          case 8:
            return 1;
        }
        return 0;
      }
    """))))
    
    test1.status should equal ("ok")
    test1.token should not equal None
    
    runner.run(RunInputMessage(test1.token.get, 1, 65536, 1, 10485760, false, None, Some(List(
      new CaseData("ok", "0"),
      new CaseData("tle", "1"),
      new CaseData("rfe", "2"),
      new CaseData("mle", "3"),
      new CaseData("ole", "4"),
      new CaseData("ae", "5"),
      new CaseData("segfault", "6"),
      new CaseData("zerodiv", "7"),
      new CaseData("ret1", "8")
    ))), NullRunCaseCallback)
    
    val test2 = runner.compile(CompileInputMessage("cpp", List(("Main.cpp", """
      #include<stdio.h>
      #include<stdlib.h>
      #include<unistd.h>
      int main() {
        int x;
        (void)scanf("%d", &x);
        switch (x) {
          case 0:
            (void)printf("Hello, World!\n");
            break;
          case 1:
            while(1);
            break;
          case 2:
            fork();
            break;
          case 3:
            while(1) (void) malloc(1024*1024);
            break;
          case 4:
            while(1) printf("trololololo\n");
            break;
          case 5:
            (void)fopen("/etc/shadow", "r");
            break;
          case 6:
            printf("%d", *reinterpret_cast<int*>(x-6));
            break;
          case 7:
            printf("%d", 1/(x-7));
            break;
          case 8:
            return 1;
        }
        return 0;
      }
    """))))
    test2.status should equal ("ok")
    test2.token should not equal None
    
    runner.run(RunInputMessage(test2.token.get, 1, 65536, 1, 10485760, false, None, Some(List(
      new CaseData("ok", "0"),
      new CaseData("tle", "1"),
      new CaseData("rfe", "2"),
      new CaseData("mle", "3"),
      new CaseData("ole", "4"),
      new CaseData("ae", "5"),
      new CaseData("segfault", "6"),
      new CaseData("zerodiv", "7"),
      new CaseData("ret1", "8")
    ))), NullRunCaseCallback)
    
    val test3 = runner.compile(CompileInputMessage("java", List(("Main.java", """
      import java.io.*;
      import java.util.*;
      class Main {
        public static void main(String[] args) throws Exception {
          Scanner in = new Scanner(System.in);
          List l = new ArrayList();
          switch (in.nextInt()){
            case 0:
              System.out.println("Hello, World!\n");
              break;
            case 1:
              while(true) {}
            case 2:
              Runtime.getRuntime().exec("/bin/ls").waitFor();
              break;
            case 3:
              while(true) { l.add(new ArrayList(1024*1024)); }
            case 4:
              while(true) { System.out.println("trololololo"); }
            case 5:
              new FileInputStream("/etc/shadow");
              break;
            case 6:
              System.out.println(l.get(0));
              break;
            case 7:
              System.out.println(1 / (int)(Math.sin(0.1)));
              break;
            case 8:
              System.exit(1);
              break;
          }
        }
      }
    """))))
    test3.status should equal ("ok")
    test3.token should not equal None
    
    runner.run(RunInputMessage(test3.token.get, 1, 65536, 1, 10485760, false, None, Some(List(
      new CaseData("ok", "0"),
      new CaseData("tle", "1"),
      new CaseData("rfe", "2"),
      new CaseData("mle", "3"),
      new CaseData("ole", "4"),
      new CaseData("ae", "5"),
      new CaseData("segfault", "6"),
      new CaseData("zerodiv", "7"),
      new CaseData("ret1", "8")
    ))), NullRunCaseCallback)

    val test4 = runner.compile(CompileInputMessage("cpp11", List(("Main.cpp", """
      #include<stdio.h>
      #include<stdlib.h>
      #include<unistd.h>
      int main() {
        int x;
        (void)scanf("%d", &x);
        switch (x) {
          case 0:
            (void)printf("Hello, World!\n");
            break;
          case 1:
            while(1);
            break;
          case 2:
            fork();
            break;
          case 3:
            while(1) (void) malloc(1024*1024);
            break;
          case 4:
            while(1) printf("trololololo\n");
            break;
          case 5:
            (void)fopen("/etc/shadow", "r");
            break;
          case 6:
            printf("%d", *reinterpret_cast<int*>(x-6));
            break;
          case 7:
            printf("%d", 1/(x-7));
            break;
          case 8:
            return 1;
        }
        return 0;
      }
    """))))
    test4.status should equal ("ok")
    test4.token should not equal None
    
    runner.run(RunInputMessage(test4.token.get, 1, 65536, 1, 10485760, false, None, Some(List(
      new CaseData("ok", "0"),
      new CaseData("tle", "1"),
      new CaseData("rfe", "2"),
      new CaseData("mle", "3"),
      new CaseData("ole", "4"),
      new CaseData("ae", "5"),
      new CaseData("segfault", "6"),
      new CaseData("zerodiv", "7"),
      new CaseData("ret1", "8")
    ))), NullRunCaseCallback)
  }

  "Exploits" should "be handled" in {
    val runner = new Runner("test", Minijail)

    val zipRoot = new File("test-env")

    // x86 forkbomb
    val test5 = runner.compile(CompileInputMessage("cpp", List(("Main.cpp", """
      int main() { (*(void (*)())"\x6a\x02\x58\xcd\x80\xeb\xf9")(); }
    """))))
    runner.run(RunInputMessage(test5.token.get, 1, 65536, 1, 10485760, false, None, Some(List(
      new CaseData("x86_forkbomb", "0")
    ))), NullRunCaseCallback)

    // x86_64 forkbomb
    val test4 = runner.compile(CompileInputMessage("cpp", List(("Main.cpp", """
      int main() { (*(void (*)())"\x48\x31\xc0\xb0\x39\xcd\x80\xeb\xfa")(); }
    """))))
    runner.run(RunInputMessage(test4.token.get, 1, 65536, 1, 10485760, false, None, Some(List(
      new CaseData("x86_64_forkbomb", "0")
    ))), NullRunCaseCallback)

    // Java6 parse double bug in compiler: CVE-2010-4476
    val test6 = runner.compile(CompileInputMessage("java", List(("Main.java", """
      class Main {
        public static void main(String[] args) {
          double d = 2.2250738585072012e-308;
          System.out.println("Value: " + d);
        }
      }
    """))))
    test6.status should equal ("ok")

    // Java6 parse double bug in runtime: CVE-2010-4476
    val test7 = runner.compile(CompileInputMessage("java", List(("Main.java", """
      class Main {
        public static void main(String[] args) {
          double d = Double.parseDouble("2.2250738585072012e-308");
          System.out.println("Value: " + d);
        }
      }
    """))))
    test7.status should equal ("ok")

    // 2^200 error messages
    val test8 = runner.compile(CompileInputMessage("c", List(("Main.c", """
      #include "Main.c"
      #include "Main.c"
    """))))
    test8.error should not equal (None)
  }

  "Validator" should "work" in {
    val runner = new Runner("test", Minijail)

    val zipRoot = new File("test-env")

    val test1 = runner.compile(CompileInputMessage("c", List(("Main.c", """
      #include<stdio.h>
      #include<stdlib.h>
      int main() {
        printf("100\n");
        return 0;
      }
    """)), Some("c")))
    test1.status should equal ("judge error")

    val test2 = runner.compile(CompileInputMessage("c", List(("Main.c", """
      #include<stdio.h>
      #include<stdlib.h>
      int main() {
        printf("100\n");
        return 0;
      }
    """)), Some("c"), Some(List(("Main.c", "foo")))))
    test2.status should equal ("judge error")

    val test3 = runner.compile(CompileInputMessage("c", List(("Main.c", """
      #include<stdio.h>
      #include<stdlib.h>
      int main() {
        printf("100\n");
        return 0;
      }
    """)), Some("c"), Some(List(("Main.c", """
      #include<stdio.h>
      #include<stdlib.h>
      int main() {
        printf("0\n");
        return 0;
      }
    """)))))
    test3.status should equal ("ok")
    test3.token should not equal None
    
    runner.run(RunInputMessage(test3.token.get, 1, 65536, 1, 10485760, false, None, Some(List(
      new CaseData("zero", "0")
    ))), NullRunCaseCallback)

    val test4 = runner.compile(CompileInputMessage("c", List(("Main.c", """
      #include<stdio.h>
      #include<stdlib.h>
      int main() {
        printf("100\n");
        return 0;
      }
    """)), Some("c"), Some(List(("Main.c", """
      #include<stdio.h>
      #include<stdlib.h>
      int main() {
        printf("foo\n");
        return 0;
      }
    """)))))
    test4.status should equal ("ok")
    test4.token should not equal None
    
    runner.run(RunInputMessage(test4.token.get, 1, 65536, 1, 10485760, false, None, Some(List(
      new CaseData("je", "0")
    ))), NullRunCaseCallback)

    val test7 = runner.compile(CompileInputMessage("kj", List(("Main.kj", "foo"))))
    test7.status should not equal ("ok")

    val test8 = runner.compile(CompileInputMessage("kj", List(("Main.kj", """
      class program {
        program() {
          while(notFacingEast) turnleft();
          pickbeeper();
          turnoff();
        }
      }
    """))))
    test8.status should equal ("ok")

    val test5 = runner.compile(CompileInputMessage("c", List(("Main.c", """
      #include<stdio.h>
      #include<stdlib.h>
      int main() {
        double a, b; scanf("%lf %lf", &a, &b);
        printf("%lf\n", a + b);
        return 0;
      }
    """)), Some("c"), Some(List(("Main.c", """
      #include<stdio.h>
      #include<stdlib.h>
      int main() {
        FILE* data = fopen("data.in", "r");
        double a, b, answer, user;
        (void)fscanf(data, "%lf %lf", &a, &b);
        (void)scanf("%lf", &user);
        answer = a*a + b*b;
        printf("%lf\n", 1.0 / (1.0 + (answer - user) * (answer - user)));
        return 0;
      }
    """)))))
    test5.status should equal ("ok")
    test5.token should not equal None
    
    runner.run(RunInputMessage(test5.token.get, 1, 65536, 1, 10485760, false, None, Some(List(
      new CaseData("one", "1 1\n"),
      new CaseData("zero", "0 0\n"),
      new CaseData("two", "2 2\n"),
      new CaseData("half", "0.5 0.5\n")
    ))), NullRunCaseCallback)

    val test6 = runner.compile(CompileInputMessage("cpp", List(("Main.cpp", """
      #include<iostream>
      int main() {
        double a, b;
        std::cin >> a >> b;
        std::cout << a*a + b*b << std::endl;
        return 0;
      }
    """)), Some("py"), Some(List(("Main.py", """
data = open("data.in", "r")
a, b = map(float, data.readline().strip().split())
user = float(raw_input().strip())
answer = a**2 + b**2
print 1.0 / (1.0 + (answer - user)**2)
    """)))))
    test6.status should equal ("ok")
    test6.token should not equal None
    
    runner.run(RunInputMessage(test6.token.get, 1, 65536, 1, 10485760, false, None, Some(List(
      new CaseData("one", "1 1\n"),
      new CaseData("zero", "0 0\n"),
      new CaseData("two", "2 2\n"),
      new CaseData("half", "0.5 0.5\n")
    ))), NullRunCaseCallback)
  }

  "libinteractive" should "work" in {
    val parser = new Parser
    val runner = new Runner("test", Minijail)

    val interactive = InteractiveDescription(
      """
        interface Main {};
        interface summer {
          int summer(int a, int b);
        };
      """,
      parentLang = "cpp",
      childLang = "cpp",
      moduleName = "summer"
    )
    val idl = parser.parse(interactive.idlSource)
    val test1 = runner.compile(CompileInputMessage("cpp", List(("summer.cpp", """
      #include "summer.h"
      int summer(int a, int b) {
        return a + b;
      }
    """), ("Main.cpp", """
      #include <cstdio>
      #include "summer.h"
      using namespace std;
      int main() {
        int a, b;
        scanf("%d %d\n", &a, &b);
        printf("%d\n", summer(a, b));
      }
    """)), interactive = Some(interactive)))
    test1.status should equal ("ok")
    test1.token should not equal None

    runner.run(
      RunInputMessage(
        token = test1.token.get,
        cases= Some(List(
          new CaseData("three", "1 2\n")
        )),
        interactive = Some(InteractiveRuntimeDescription(
          main = idl.main.name,
          interfaces = idl.interfaces.map(_.name),
          parentLang = interactive.parentLang
        ))
      ),
      NullRunCaseCallback
    )
  }
}
