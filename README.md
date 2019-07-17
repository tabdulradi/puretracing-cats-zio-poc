A POC for the new Puretracing supporting Cats-Effect & ZIO (without Monad transformers)
To be merged back to [Puretracing](https://github.com/tabdulradi/puretracing) project.

# Instructions

1. Run Jaeger 
```bash
docker run -d --name jaeger \
  -e COLLECTOR_ZIPKIN_HTTP_PORT=9411 \
  -p 5775:5775/udp \
  -p 6831:6831/udp \
  -p 6832:6832/udp \
  -p 5778:5778 \
  -p 16686:16686 \
  -p 14268:14268 \
  -p 9411:9411 \
  jaegertracing/all-in-one:1.8
```  

2. `sbt "runMain com.example.http4sexample.JokesProxy"`
3. `sbt "runMain com.example.http4sexample.ZIOMain"` (or CatsMain)
4.  `curl -v localhost:8080/joke`
5. Verify that JokesProxy has logged a request with "uber-trace-id" in the headers
6. Open [Jaeger UI](localhost:16686/), then from the first drop down menu select "zio-puretracing-example", then click find traces. You should a Trace with 2 spans, the inner one is called "get-joke".
7. Check Jokes.scala line 44 to see how that span was created.

Note: Code is written in Tagless final style to allow reuse between ZIO and Cats versions. But Tagless final is not required, and in case of ZIO no monad transformers are required either.