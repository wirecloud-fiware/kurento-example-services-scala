Kurento example Service for [Scala](http://www.scala-lang.org/)
===

This project are examples services for Kurento in Scala.

It uses [Play Framework](https://www.playframework.com/) as a web framework to run the websocket server and [Akka](http://akka.io/) actors in communications.

That make the service powerful and concurrent.

If you want to learn more about it, search and read about [Reactive Programming](http://en.wikipedia.org/wiki/Reactive_programming) and [Actor Model](http://en.wikipedia.org/wiki/Actor_model).

Installation
===

The services use [Scala](http://www.scala-lang.org/), [sbt](http://www.scala-sbt.org/) and [Play Framework](https://www.playframework.com/).

Install [Scala 2.11](http://www.scala-lang.org/download/) first.
Then [sbt](http://www.scala-sbt.org/download.html).
And last [Play 2.3](https://www.playframework.com/download) (Tested in Play 2.3.8)


Test
===

Once you have all installed, you can check your installation with the tests.

To execute the tests, you need the [Kurento Media Server Mock]().

Execute the kms-mock, and run the tests.

To run the test type this command:
```
activator test
```

If it's the first time you execute activator that involves the project, it will download the proper version of sbt and all the dependencies.

You should see that the tests pass.


Execution
===

To execute the service, you can type this command:

```
activator run
```

If you want to execute it in a custom port (by default is 9000) execute:

```
activator "run <port>"
activator "run 8082" # Example
```

Stop the service with Ctrl-D.



Configuration
===

The configuration is made in `conf/application.conf`.

Values:

- application.secret = <secret> => Use a proper secret if used in production. Generate with `activator play-generate-secret`
- kurento.ws = <websocket-url> => Kurento Media Server path
- kurento.test.ws = <local-websocket-url> => Kurento Media Server Mock path
