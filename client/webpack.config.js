const ScalaJS = require("./scalajs.webpack.config");
const { merge } = require("webpack-merge");

const path = require("path");
const rootDir = path.resolve(__dirname, "../..");

const WebApp = merge(ScalaJS, {
  output: {
    path: path.resolve(rootDir, "classes", "js")
  }
});

module.exports = WebApp;