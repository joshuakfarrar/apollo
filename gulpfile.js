/* gulpfile.js */

const uswds = require("@uswds/compile");

/**
 * USWDS version
 * Set the version of USWDS you're using (2 or 3)
 */

uswds.settings.version = 3;

/**
 * Path settings
 * Set as many as you need
 */

let mainPath = './client/src/main';
let resourcesPath = `${mainPath}/resources`;

uswds.paths.dist.img = `${resourcesPath}/img`;
uswds.paths.dist.fonts = `${resourcesPath}/fonts`;
uswds.paths.dist.js = `${resourcesPath}/js`;
uswds.paths.dist.css = `${resourcesPath}/css`;
uswds.paths.dist.theme = `${mainPath}/sass`;

/**
 * Exports
 * Add as many as you need
 */

exports.init = uswds.init;
exports.compile = uswds.compile;