"""Freeze public-science pilot inputs separately from held-out evaluation labels."""
from pathlib import Path
import argparse, json, random, hashlib, re, math, collections, importlib.util

def tokens(text): return re.findall(r'[a-z0-9]+',text.lower())

def bm25(question, paragraphs, k=6, budget=9000):
    docs=[collections.Counter(tokens(p['text'])) for p in paragraphs]
    n=len(docs); avg=sum(map(lambda d:sum(d.values()),docs))/max(n,1)
    df=collections.Counter(w for d in docs for w in d)
    q=set(tokens(question)); ranked=[]
    for i,d in enumerate(docs):
        score=sum(math.log(1+(n-df[w]+.5)/(df[w]+.5))*d[w]*2.2/(d[w]+1.2*(.25+.75*sum(d.values())/max(avg,1))) for w in q if d[w])
        ranked.append((-score,i))
    out=[]; used=0
    for _,i in sorted(ranked):
        p=dict(paragraphs[i]); available=budget-used
        if available<300: break
        # Source IDs retain the full paragraph identity; text truncation is recorded.
        text=p['text'][:min(2700,available)]
        p['input_truncated']=len(text)<len(p['text']); p['text']=text
        out.append(p); used+=len(text)
        if len(out)==k: break
    return out

def load_lines(p): return [json.loads(s) for s in p.read_text(encoding='utf-8').splitlines() if s]

def prepare(root):
    raw=root/'raw'; rng=random.Random(20261010)
    corpus={str(r['doc_id']):r for r in load_lines(raw/'corpus.jsonl')}
    dev=load_lines(raw/'claims_dev.jsonl'); train=load_lines(raw/'claims_train.jsonl')
    pools=collections.defaultdict(list)
    for c in dev:
        # Only a single supplied cited abstract, avoiding repeated claims and conflicting documents.
        if len(c['cited_doc_ids'])!=1: continue
        doc=str(c['cited_doc_ids'][0]); anns=c['evidence'].get(doc,[])
        labels={a['label'] for a in anns}
        if len(labels)>1: continue
        label=next(iter(labels)) if labels else 'NOT_ENOUGH_INFO'
        paragraphs=[{'id':f's{i:03d}','text':s} for i,s in enumerate(corpus[doc]['abstract'])]
        pools[label].append((c,doc,anns,paragraphs))
    inputs=[]; gold=[]
    for label in ['SUPPORT','CONTRADICT','NOT_ENOUGH_INFO']:
        pool=pools[label]; rng.shuffle(pool)
        for c,doc,anns,paragraphs in pool[:10]:
            cid=f"scifact-{c['id']}-{doc}"
            inputs.append({'id':cid,'dataset':'SciFact','question':c['claim'],'title':corpus[doc]['title'],'paragraphs':paragraphs})
            gold.append({'id':cid,'dataset':'SciFact','claim_id':c['id'],'document_id':doc,'label':label,
                         'evidence_sets':[[f's{i:03d}' for i in a['sentences']] for a in anns] or [[]]})
    assert len(inputs)==30
    spec=importlib.util.spec_from_file_location('qasper_official',raw/'qasper_evaluator.py'); ev=importlib.util.module_from_spec(spec); spec.loader.exec_module(ev)
    qdev=json.loads((raw/'qasper-dev-v0.3.json').read_text(encoding='utf-8'))
    qtrain=json.loads((raw/'qasper-train-v0.3.json').read_text(encoding='utf-8'))
    refs=ev.get_answers_and_evidence(qdev,False); qpools=collections.defaultdict(list)
    for pid,p in qdev.items():
        paras=[{'id':f'p{i:03d}','text':text} for i,text in enumerate([p['abstract']]+[t for sec in p['full_text'] for t in sec['paragraphs']]) if text.strip()]
        for qa in p['qas']:
            rr=refs[qa['question_id']]; types={r['type'] for r in rr}
            # Exclude annotator disagreements about answerability and mixed answer type.
            if len(types)!=1: continue
            typ=next(iter(types))
            if any('FLOAT SELECTED' in t for r in rr for t in r['evidence']):continue
            # Retain questions with exact paragraph-level text evidence mapping, independently of retrieval.
            text_ids={re.sub(r'\s+',' ',x['text']).strip():x['id'] for x in paras}
            if any(re.sub(r'\s+',' ',t).strip() not in text_ids for r in rr for t in r['evidence']): continue
            qpools[typ].append((pid,p,qa,rr,paras,text_ids))
    selected=[]; used_papers=set()
    for typ,n in [('extractive',8),('abstractive',8),('boolean',8),('none',6)]:
        pool=qpools[typ]; rng.shuffle(pool); accepted=0
        for pid,p,qa,rr,paras,text_ids in pool:
            if pid in used_papers:continue
            used_papers.add(pid); selected.append((pid,p,qa,rr,paras,text_ids)); accepted+=1
            if accepted==n:break
        assert accepted==n,(typ,accepted)
    for pid,p,qa,rr,paras,text_ids in selected:
        cid='qasper-'+qa['question_id']; chosen=bm25(qa['question'],paras)
        inputs.append({'id':cid,'dataset':'QASPER','question':qa['question'],'title':p['title'],'paragraphs':chosen})
        gold.append({'id':cid,'dataset':'QASPER','paper_id':pid,'question_id':qa['question_id'],
                     'references':[dict(r,evidence_ids=[text_ids[re.sub(r'\s+',' ',t).strip()] for t in r['evidence']]) for r in rr],
                     'all_paragraphs':paras})
    train_ids={c['id'] for c in train}; assert not train_ids.intersection(g['claim_id'] for g in gold if g['dataset']=='SciFact')
    assert not set(qtrain).intersection(used_papers)
    # Proxy feedback is authored from training-only examples, not held-out predictions or labels.
    sci_training=train[:3]; q_training=list(qtrain.items())[:3]
    feedback={'SciFact':[
       {'lesson':'核验科学结论时先比较方向、研究对象和条件；摘要中出现同一术语不等于支持该结论。没有支持或反驳的原文时应明确证据不足。','reuse_scope':'project_local','origin_kind':'training_annotation_proxy_feedback','training_ids':[c['id'] for c in sci_training]},
       {'lesson':'不要把关联关系写成因果关系，不要忽略否定、数值和比较条件。只选择真正支持判断的句子；证据不足时不要引用无关句子。','reuse_scope':'project_local','origin_kind':'training_annotation_proxy_feedback','training_ids':[c['id'] for c in sci_training]}],
      'QASPER':[
       {'lesson':'回答论文问题时优先摘录与问题直接对应的短答案，不要加入多余背景；是非问题明确回答Yes或No，全文未提供信息时回答Unanswerable。','reuse_scope':'project_local','origin_kind':'training_annotation_proxy_feedback','training_papers':[pid for pid,_ in q_training]},
       {'lesson':'回答实验设置、数据集和评价指标时核对原始段落，不混淆训练数据与测试数据。引用只选择支持答案的段落，不能用标题或常识代替论文证据。','reuse_scope':'project_local','origin_kind':'training_annotation_proxy_feedback','training_papers':[pid for pid,_ in q_training]}]}
    # Record the actual training samples so feedback provenance can be audited.
    (root/'training-feedback-source.json').write_text(json.dumps({'SciFact':sci_training,'QASPER':[{'paper_id':pid,'title':p['title'],'qas':p['qas']} for pid,p in q_training]},ensure_ascii=False,indent=2),encoding='utf-8')
    frozen={'protocol':'public-science-pilot-v1','seed':20261010,'cases':inputs,'training_feedback':feedback}
    for name,data in [('inputs.json',frozen),('gold.json',gold)]:
        (root/name).write_text(json.dumps(data,ensure_ascii=False,indent=2),encoding='utf-8')
    manifest={'protocol':frozen['protocol'],'seed':20261010,'scifact':{'corpus_abstracts':len(corpus),'train_claims':len(train),'dev_claims':len(dev),'eligible_pools':{k:len(v) for k,v in pools.items()},'sample':30,'label_counts':{k:10 for k in pools}},
              'qasper':{'train_papers':len(qtrain),'dev_papers':len(qdev),'dev_questions':len(refs),'eligible_pools':{k:len(v) for k,v in qpools.items()},'sample':30,'paper_count':len(used_papers),'type_counts':{'extractive':8,'abstractive':8,'boolean':8,'none':6},'retrieval':'BM25 top 6; 9000 character evidence budget; max 2700 characters per paragraph'},
              'input_sha256':hashlib.sha256((root/'inputs.json').read_bytes()).hexdigest(),'gold_sha256':hashlib.sha256((root/'gold.json').read_bytes()).hexdigest()}
    (root/'protocol-manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(manifest,ensure_ascii=False,indent=2))

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('root',type=Path);prepare(p.parse_args().root)
