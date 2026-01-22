// Browser mocks for Node.js modules (required by Okio/Coil)
const path = require('path');
config.resolve = config.resolve || {};
config.resolve.alias = config.resolve.alias || {};
config.resolve.alias.os = path.resolve(__dirname, 'mocks/os-mock.js');
config.resolve.alias.path = path.resolve(__dirname, 'mocks/path-mock.js');
