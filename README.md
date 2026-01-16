# Apollo

[![Scala](https://img.shields.io/badge/Scala%203-%23de3423.svg?logo=scala&logoColor=white)](https://www.scala-lang.org/)

🚧 This code is under development and is NOT meant for production use! 🚧

An easy-to-use Devise-like authentication solution for Http4s. It will become configurable and more modular, so that you only need to use what you really need.

It has a few features:

- Database registration and authentication
- Account confirmation
- Password recovery

Long-term, we'd like to also support Omniauth and all functionality of the standard Devise module

## Modules

Apollo is split into three modules for flexibility:

| Module | Description | Dependencies |
|--------|-------------|--------------|
| `apollo-core` | Type classes and service traits | cats, cats-effect |
| `apollo-doobie` | Doobie-based database implementations | apollo-core, doobie |
| `apollo-http4s` | Http4s routes and middleware | apollo-core, http4s, twirl |

### Usage

```scala
// Just the core abstractions
mvnDeps = Seq(mvn"me.joshuakfarrar::apollo-core:0.1.0")

// With Doobie database layer
mvnDeps = Seq(mvn"me.joshuakfarrar::apollo-doobie:0.1.0")

// Full stack: Http4s routes + templates
mvnDeps = Seq(
  mvn"me.joshuakfarrar::apollo-http4s:0.1.0",
  mvn"me.joshuakfarrar::apollo-doobie:0.1.0"
)
```

## Getting Started

First, pull down Apollo and add it to your local ivy cache:

```shell
$ git clone https://github.com/joshuakfarrar/apollo
$ cd apollo
$ mill __.publishLocal
```

Then, use our fancy new [Giter8](https://www.foundweekends.org/giter8) template!

```shell
$ mill --interactive init joshuakfarrar/apollo.g8
$ cd webapp
$ # Initialize your PostgreSQL database using the scripts in apollo-doobie/src/db/pg/
```

Configure the application in `./webapp/resources/application.conf`, then, run the server:

```shell
webapp $ mill webapp.run
```

Browse to [http://localhost:8080](http://localhost:8080) *et voilà!*

We promise we'll get this on Maven Central soon.

🎉
