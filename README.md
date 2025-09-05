# Apollo

🚧 This code is under development and is NOT meant for production use! 🚧

An easy-to-use Devise-like authentication solution for Http4s. It will become configurable and more modular, so that you only need to use what you really need.

It has a few features:

- Database registration and authentication
- Account confirmation
- Password recovery

Long-term, we'd like to also support Omniauth and all functionality of the standard Devise module

## Getting Started

Use our fancy new [Giter8](https://www.foundweekends.org/giter8) template!

```shell
$ .\mill.bat --interactive init joshuakfarrar/apollo.g8
$ cd webapp
$ .\db\initalize-database.bat # ⚠️ warning: this will destroy and reinitialize your database!
```

Configure the application in `.\webapp\resources\application.conf`, then, run the server:

```shell
webapp $ .\mill.bat --no-server webapp.run
```

Browse to [http://localhost:8080](http://localhost:8080) *et voilà!*

We promise we'll get this on Maven Central and add generators to automate some of the setup soon.

🎉