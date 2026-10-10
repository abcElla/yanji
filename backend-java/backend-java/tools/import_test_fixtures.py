"""Run only during migration using the old backend environment. Synthetic data only."""
import pathlib
import sys
import shutil
import json
import jieba
from argon2 import PasswordHasher

root=pathlib.Path(__file__).resolve().parents[1]
source=root.parent/'backend'
sys.path.insert(0,str(source))
from app.service.lexical_search import query_terms, lexical_score
shutil.copyfile(pathlib.Path(jieba.__file__).parent/'dict.txt', root/'src/main/resources/jieba-dict.txt')
fixtures=json.loads((source/'tests/fixtures/lexical_queries.json').read_text(encoding='utf-8'))
for fixture in fixtures:
    fixture['terms']=list(query_terms(fixture['query']))
    fixture['score']=lexical_score(fixture['text'],fixture['terms'])
target=root/'src/test/resources'
target.mkdir(parents=True,exist_ok=True)
(target/'lexical-compatibility.json').write_text(json.dumps(fixtures,ensure_ascii=False,indent=2),encoding='utf-8')
(target/'python-argon2.txt').write_text(PasswordHasher().hash('Synthetic123'),encoding='utf-8')
print('Imported dictionary, 12 lexical compatibility cases, and synthetic Python Argon2 hash')
