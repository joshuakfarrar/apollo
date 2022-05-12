# Apollo

## A type-safe framework for ambitious web applications.

### Features

- Supports full-stack type-safe web programming using the Scala programming language
- Uses scalajs-react to provide a toolkit for developing single-page applications
- http4s can be used as a static file server or to easily power type-safe JSON APIs
- Compiles SCSS so you can still use your favorite UI kits
- Implements the USWDS Web Design System for getting started quickly building applications for the federal government

# Getting Started
## Get the code (.g8 template coming soon!)
## Starting the Server in Development Mode

Apollo will automatically watch your files for changes and recompile your application when it detects any.

```console
$ sbt
sbt:apollo> project server
sbt:server> ~reStart
```

## Install node.js modules required for Sass compilation

Installing the required node.js libraries is easy:

```console
$ npm install
```

It should be noted that application dependencies are specified and bundled into the client application via the client application's definition in `build.sbt`.


## Compiling SCSS Assets

Because Apollo ships with the [U.S. Web Design System](https://designsystem.digital.gov/), getting started with Sass is a breeze. Sass files are located in `client/src/main/sass` and can be compiled to `.css` with a quick `npx gulp compile`!

## Learning Scala

🇺🇸