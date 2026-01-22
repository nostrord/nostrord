// Browser mock for Node.js 'os' module (required by Okio/Coil)
module.exports = {
    tmpdir: () => '/tmp',
    platform: () => 'browser',
    EOL: '\n'
};
