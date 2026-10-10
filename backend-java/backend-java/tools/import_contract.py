"""Development-only import of the existing schema/HTTP contract; Java has no Python runtime dependency."""
import ast
import json
import pathlib
import shutil

root = pathlib.Path(__file__).resolve().parents[1]
source = root.parent / 'backend'
resources = root / 'src/main/resources'
tree = ast.parse((source / 'app/models.py').read_text(encoding='utf-8'))
metadata = {}
ddl = []
foreign_ddl = []
mysql_ddl = []
for cls in tree.body:
    if not isinstance(cls, ast.ClassDef):
        continue
    table = next((ast.literal_eval(n.value) for n in cls.body if isinstance(n, ast.Assign) and any(isinstance(t, ast.Name) and t.id == '__tablename__' for t in n.targets)), None)
    if not table:
        continue
    cols = {}
    definitions = []
    pks = []
    foreign = []
    unique = []
    for n in cls.body:
        if isinstance(n, ast.Assign) and any(isinstance(t, ast.Name) and t.id == '__table_args__' for t in n.targets):
            for c in ast.walk(n.value):
                if isinstance(c, ast.Call) and isinstance(c.func, ast.Name) and c.func.id == 'UniqueConstraint':
                    unique.append([ast.literal_eval(a) for a in c.args])
        if not isinstance(n, ast.AnnAssign) or not isinstance(n.value, ast.Call):
            continue
        name = n.target.id
        call = n.value
        annotation = ast.unparse(n.annotation)
        sqltype = 'varchar(255)'
        kind = 'string'
        if 'datetime' in annotation:
            sqltype, kind = 'timestamp(6)', 'datetime'
        elif 'date]' in annotation:
            sqltype, kind = 'date', 'date'
        elif 'bool' in annotation:
            sqltype, kind = 'boolean', 'boolean'
        elif 'int' in annotation:
            sqltype, kind = 'bigint', 'integer'
        if 'Text().with_variant' in ast.unparse(call):
            sqltype = 'clob'
        for a in call.args:
            typename = a.id if isinstance(a, ast.Name) else a.func.id if isinstance(a, ast.Call) and isinstance(a.func, ast.Name) else ''
            if typename == 'JSON':
                sqltype, kind = 'clob', 'json'
            if typename == 'Text':
                sqltype = 'clob'
            if typename == 'String':
                sqltype = f'varchar({ast.literal_eval(a.args[0])})'
            if typename == 'ForeignKey':
                target = ast.literal_eval(a.args[0]).split('.')
                foreign.append(f'FOREIGN KEY (`{name}`) REFERENCES `{target[0]}` (`{target[1]}`)')
        kw = {k.arg: k.value for k in call.keywords}
        primary = 'primary_key' in kw and ast.literal_eval(kw['primary_key'])
        default = None
        if 'default' in kw:
            d = kw['default']
            if isinstance(d, ast.Name):
                default = {'uid': '@uuid', 'now': '@now', 'list': [], 'dict': {}}.get(d.id)
            else:
                default = ast.literal_eval(d)
        nullable = 'None' in annotation
        cols[name] = {'kind': kind, 'default': default, 'nullable': nullable}
        definitions.append(f'`{name}` {sqltype}' + ('' if nullable else ' NOT NULL'))
        if primary:
            pks.append(name)
        if 'unique' in kw and ast.literal_eval(kw['unique']):
            unique.append([name])
    definitions += ['PRIMARY KEY (' + ','.join(f'`{p}`' for p in pks) + ')']
    definitions += ['UNIQUE (' + ','.join(f'`{p}`' for p in u) + ')' for u in unique]
    mysql_definitions = []
    for definition in definitions:
        column = definition.split('`')[1] if definition.startswith('`') else None
        if column and cols[column]['kind'] == 'json':
            definition = definition.replace(' clob', ' JSON')
        else:
            definition = definition.replace(' clob', ' LONGTEXT')
        mysql_definitions.append(definition)
    mysql_ddl.append('CREATE TABLE IF NOT EXISTS `' + table + '` (\n  ' + ',\n  '.join(mysql_definitions + foreign) + '\n) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;')
    for i, constraint in enumerate(foreign):
        foreign_ddl.append(f'ALTER TABLE `{table}` ADD CONSTRAINT IF NOT EXISTS `fk_{table}_{i}` {constraint};')
    ddl.append('CREATE TABLE IF NOT EXISTS `' + table + '` (\n  ' + ',\n  '.join(definitions) + '\n);')
    metadata[table] = cols
resources.mkdir(parents=True, exist_ok=True)
(resources / 'tables.json').write_text(json.dumps(metadata, ensure_ascii=False, indent=2), encoding='utf-8')
(resources / 'schema-h2.sql').write_text('\n\n'.join(ddl + foreign_ddl), encoding='utf-8')
shutil.copyfile(source / 'docs/fastapi-openapi.json', resources / 'openapi.json')
(root / 'sql').mkdir(exist_ok=True)
(root / 'sql/000_current_schema_mysql.sql').write_text('-- Fresh empty database only. Select your database before running.\nSET FOREIGN_KEY_CHECKS=0;\n\n' + '\n\n'.join(mysql_ddl) + '\nSET FOREIGN_KEY_CHECKS=1;\n', encoding='utf-8')
for p in (source / 'sql').glob('*.sql'):
    shutil.copyfile(p, root / 'sql' / p.name)
print(f'Imported {len(metadata)} tables and existing OpenAPI contract')
