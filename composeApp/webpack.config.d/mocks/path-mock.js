// Browser mock for Node.js 'path' module
module.exports = {
    sep: '/',
    join: (...args) => args.filter(Boolean).join('/'),
    dirname: (p) => p ? p.split('/').slice(0, -1).join('/') || '/' : '.',
    basename: (p) => p ? p.split('/').pop() : ''
};
