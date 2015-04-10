Kurento example Service for [Scala](http://www.scala-lang.org/)
===

This project are examples services for Kurento in Scala.

It uses [Play Framework](https://www.playframework.com/) as a web framework to run the websocket server and [Akka](http://akka.io/) actors in communications.

That make the service powerful and concurrent.

If you want to learn more about it, search and read about [Reactive Programming](http://en.wikipedia.org/wiki/Reactive_programming) and [Actor Model](http://en.wikipedia.org/wiki/Actor_model).

Right now are two WireCloud widgets that use this service: [One2One Call Widget](https://github.com/wirecloud-fiware/kurento-one2one-widget) and [CrowdDetector Widget](https://github.com/wirecloud-fiware/kurento-crowddetector-widget).

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

You have different ways to do this:

- Debug and develop

    To execute the service in this mode, you can type this command:

    ```
    activator run
    ```

    If you want to execute it in a custom port (by default is 9000) execute:

    ```
    activator "run <port>"
    activator "run 8082" # Example
    ```

    You can stop the service with Ctrl-D.

    Executed in this mode, in every petition will look for changes in the source code and do an incremental compilation before to attend the petition. This is so useful for development, but not for production.

- Production.

    To execute an Play app in production you have several ways (as seen [here](https://www.playframework.com/documentation/2.3.x/Production)).

  Before executing in production, be sure that you are setting the application.secret configuration, or your communications.

  Check [here](https://www.playframework.com/documentation/2.3.x/ApplicationSecret) to see how to create and set the application secret.

  The most useful are this ones:
    - Start command:
    ```
    activator start -Dapplication.secret=abcdefghijk
    ```
    With this command you start the application and see the log in the terminal.

    If you press `Ctrl+D` the server will be in background, and the application main logs will go to logs/application.log

    If you press `Ctrl+C`, you will kill the console and the server.
    - Stage task:
      ```
      activator clean stage
      ```
      This command will create the directory: `target/universal/stage`.

      In this directory you will have all the binaries and libraries that you need, and te execute it you only have to do:
      ``` bash
      ./target/universal/stage/bin/<app-name> -Dconfig.file=/full/path/to/conf/application-prod.conf
      ```
    - Standalone version.

    You can create an standalone version of your application, if you want to know more see [here](https://www.playframework.com/documentation/2.3.x/ProductionDist)

We recommend the following way, using stage building and [pm2](https://github.com/Unitech/pm2).

- Create the stage app.

    ```
activator clean stage
```
- Next, create a bash script in your application main path to execute the application with all the flags you need. In our case is something like this:

    ``` bash
# file execute-prod.sh
./target/universal/stage/bin/<app_name> -Dconfig.file=conf/application.conf -Dhttp.port=disabled -Dhttps.port=443 -Dhttps.address=0.0.0.0 -Dhttps.keyStore=<path to keyStore> -Dhttps.keyStorePassword="If you have, set here" -Dapplication.secret='Here will be your secret'
```

- We are going to use [pm2](https://github.com/Unitech/pm2) as production process manager, you can use the one you want.

    Let's create a configuration file to pm2 (or update the one you have) see [here](https://github.com/Unitech/PM2/blob/master/ADVANCED_README.md#json-app-declaration) to know more about this files.

    ``` json
// file processes.json
{
    "apps" : [
                {
                        "name"        : "TheNameThatWillBeShowed",
                        "script"      : "execute-prod.sh",
                        "args"        : [""],
                        "log_date_format"  : "YYYY-MM-DD HH:mm Z",
                        "ignore_watch" : ["[\\/\\\\]\\./"],
                        "watch"       : true,
                        "cwd"         : "/full/path/to/your/app/main/path",
                        "max_restarts": 10, // Set the number you want, by default 15
                        "max_memory_restart": "1G", // You can set the size you want in B,M,G,...
                        "exec_interpreter": "bash",
                        "env": {}
                }
        ]
}
```
- And now only left add the app to pm2 and let it run :-)

    ``` bash
pm2 start processes.json
pm2 list
┌────────────────────┬────┬──────┬───────┬────────┬─────────┬────────┬────────────┬──────────┐
│ App name           │ id │ mode │ pid   │ status │ restart │ uptime │ memory     │ watching │
├────────────────────┼────┼──────┼───────┼────────┼─────────┼────────┼────────────┼──────────┤
│ YourAppName        │ 0  │ fork │ 29711 | online │ 0       │ 1m     │ 100.1MB    │  enabled │
└────────────────────┴────┴──────┴───────┴────────┴─────────┴────────┴────────────┴──────────┘
```


Configuration
===

The configuration is made in `conf/application.conf`.

Values:

- Play configuration:
  - application.secret = <secret> => Use a proper secret if used in production. See previous sections.
  - http.port=<http port>
  - http.address=<address to http>
  - https.port=<https port>
  - https.address=<address to https>
  - https.keyStore=<path to keystone if use it>
  - https.keyStorePassword=<password of keyStore if use it and have>
- Custom app configuration:
  - kurento.ws = <websocket-url> => Kurento Media Server path
  - kurento.test.ws = <local-websocket-url> => Kurento Media Server Mock path
