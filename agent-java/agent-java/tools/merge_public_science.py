"""Merge independent evaluation partitions; shared resume rows must be identical."""
from pathlib import Path
import json, argparse

def merge(root):
    inputs=json.loads((root/'inputs.json').read_text(encoding='utf-8'))
    expected={(c['id'],v) for c in inputs['cases'] for v in ['baseline','constrained','feedback']}
    paths=[root/'observations.SciFact.json']
    shards=sorted(root.glob('observations.QASPER.shard*.json'))
    paths+=shards or [root/'observations.QASPER.json']
    rows={}
    for path in paths:
        if not path.exists():continue
        for row in json.loads(path.read_text(encoding='utf-8')):
            key=(row['case_id'],row['variant'])
            assert key in expected,(path,key)
            if key in rows:assert rows[key]==row,('Non-identical duplicate',key)
            rows[key]=row
    assert set(rows)<=expected
    ordered=[rows[(c['id'],v)] for c in inputs['cases'] for v in ['baseline','constrained','feedback'] if (c['id'],v) in rows]
    (root/'observations.json').write_text(json.dumps(ordered,ensure_ascii=False,indent=2),encoding='utf-8')
    print('unique_saved_observations',len(rows),'expected',len(expected),'missing',len(expected-set(rows)))

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('root',type=Path);merge(p.parse_args().root)
