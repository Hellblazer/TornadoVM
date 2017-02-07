
# Installing Tornado #

  $ git clone https://bitbucket.org/clarksoj/tornado_maven.git tornado
  $ cd tornado
  $ git checkout develop
  $ vi etc/tornado.env
  $ (copy and paste the following - but update paths)

```bash
#!/bin/bash

export JAVA_HOME=<path to jvmci 8 jdk>
export GRAAL_ROOT=<path to graal.jar>
export TORNADO_ROOT=<path to cloned git dir>

if [ ! -z "${PATH}" ]; then
        export PATH="${PATH}:${TORNADO_ROOT}/bin"
else
        export PATH="${TORNADO_ROOT}/bin"
fi
```

  $ . etc/tornado.env
  $ mvn -DskipTests package
  $ cd drivers/opencl/jni-bindings
  $ autoreconf -f -i -s
  $ ./configure --prefix=${PWD} --with-jdk=${JAVA_HOME}
  ...
  $ make && make install

Complete

# Running Examples #

  [Optional]
  $ . etc/tornado.env
  
  $ tornado tornado.examples.HelloWorld


# Running Benchmarks #

  $ tornado tornado.benchmarks.BenchmarkRunner tornado.benchmarks.sadd.Benchmark

