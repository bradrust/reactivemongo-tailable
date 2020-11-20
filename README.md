To follow the steps in this tutorial, you will need the correct version of Java and sbt. The template requires:

* Java Software Developer's Kit (SE) 1.8 or higher
* sbt 1.3.4 or higher. Note: if you downloaded this project as a zip file from <https://developer.lightbend.com>, the file includes an sbt distribution for your convenience.

## Build and run the project

This example Play project was created from a seed template. It includes all Play components and an Akka HTTP server. 

To build and run the project:

1. Use a command window to change into the example project directory

2. Build the project. Enter: `sbt run`. The project builds and starts the embedded HTTP server. 

3. After the message `Server started, ...` displays, enter the following URL in a browser: <http://localhost:9000>

4. From a command line with curl: post a message which should be echoed to the browser forever-loading-page from step 3.
      curl  --request POST http://localhost:9000/post --data '{}'
      
