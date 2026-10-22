'use strict';
// Each property of a const ROUTES is a :route/<key> entity; its path must
// start with the :prefix meta-source.edn gives the pack.
const routes = (ctx) => {
  const { ts } = ctx;
  return ctx.vars().filter((v) => v.table === 'ROUTES').map((v) => {
    const sf = ctx.sourceFile(v.file);
    let path = null;
    (function visit(n) {
      if (path === null && (ts.isStringLiteral(n) || ts.isNoSubstitutionTemplateLiteral(n)) &&
          sf.getLineAndCharacterOfPosition(n.getStart(sf)).line + 1 === v.line) path = n.text;
      ts.forEachChild(n, visit);
    })(sf);
    return { v, path };
  });
};

module.exports = {
  id: 'routes',
  entities: (ctx) => routes(ctx).map(({ v, path }) => ({
    id: ':route/' + v.key, file: ctx.relative(v.file), line: v.line,
    entry: new Map([[ctx.kw(':route/path'), path]]),
  })),
  check: (ctx) => routes(ctx)
    .filter(({ path }) => path && !path.startsWith(ctx.opts.get(ctx.kw(':prefix'))))
    .map(({ v, path }) => ({ severity: 'warning', problem: 'route-outside-prefix', 'dev-id': ':route/' + v.key,
                             file: ctx.relative(v.file), line: v.line, message: `${path} is outside ${ctx.opts.get(ctx.kw(':prefix'))}` })),
  describe: (ctx, _id, entry) => entry.get(ctx.kw(':route/path')) || null,
};
