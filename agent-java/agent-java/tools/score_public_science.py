"""Score all frozen rows, including failures, against separate public gold labels."""
from pathlib import Path
import argparse, json, importlib.util, re, random, math, collections, hashlib

def marker(summary, name):
    m=re.search(r'(?im)\b'+name+r'\s*(?:\*\*)?\s*[:：]\s*([^\r\n]+)',summary)
    value=m.group(1).strip().strip('`*') if m else ''
    return re.split(r'\bFINAL_(?:LABEL|ANSWER|EVIDENCE)\s*[:：]',value)[0].strip().rstrip('。；')

def parsed(row, example):
    summary=row.get('answer',{}).get('result',{}).get('summary','')
    answer_object=row.get('answer',{}); result=answer_object.get('result',{})
    def value(name):
        v=marker(summary,name) or result.get(name,'') or answer_object.get(name,'')
        if isinstance(v,list):return ','.join(str(x) for x in v)
        return str(v).strip().strip('`*').rstrip('。；')
    evidence=value('FINAL_EVIDENCE')
    requested=re.findall(r'\b[sp]\d{3}\b',evidence)
    valid={p['id'] for p in example['paragraphs']}
    selected=sorted(set(requested)&valid)
    label=value('FINAL_LABEL').upper()
    if label not in ['SUPPORT','CONTRADICT','NOT_ENOUGH_INFO']:label='INVALID'
    answer=value('FINAL_ANSWER')
    return {'label':label,'answer':answer,'evidence_ids':selected,'invalid_evidence_ids':sorted(set(requested)-valid),
            'format_valid':bool(label!='INVALID' if example['dataset']=='SciFact' else answer) and bool(evidence)}

def macro_f1(gold,pred,labels):
    vals=[]
    for l in labels:
        tp=sum(g==l and p==l for g,p in zip(gold,pred));fp=sum(g!=l and p==l for g,p in zip(gold,pred));fn=sum(g==l and p!=l for g,p in zip(gold,pred))
        vals.append(2*tp/(2*tp+fp+fn) if 2*tp+fp+fn else 0)
    return sum(vals)/len(vals)

def mean(a):return sum(a)/len(a) if a else 0

def paired_interval(a,b):
    rng=random.Random(20261010);d=[x-y for x,y in zip(a,b)];n=len(d)
    vals=sorted(mean([d[rng.randrange(n)] for _ in range(n)]) for _ in range(10000))
    return {'difference':mean(d),'bootstrap_95_percentile_interval':[vals[249],vals[9749]],'n':n}

def score(root):
    inp=json.loads((root/'inputs.json').read_text(encoding='utf-8')); inputs={c['id']:c for c in inp['cases']}
    gold=json.loads((root/'gold.json').read_text(encoding='utf-8'))
    obs=json.loads((root/'observations.json').read_text(encoding='utf-8'))
    expected_hash=hashlib.sha256((root/'inputs.json').read_bytes()).hexdigest()
    rows={}
    for r in obs:
        assert r['input_sha256']==expected_hash
        key=(r['case_id'],r['variant']);assert key not in rows;rows[key]=r
    spec=importlib.util.spec_from_file_location('official_qasper',root/'raw'/'qasper_evaluator.py');ev=importlib.util.module_from_spec(spec);spec.loader.exec_module(ev)
    results={}; records=[]; scores={}
    for name in ['SciFact','QASPER']:
        examples=[g for g in gold if g['dataset']==name]; result={'n':len(examples),'variants':{}}
        for variant in ['baseline','constrained','feedback']:
            selected_rows=[];predlabels=[];glabels=[]; accuracy=[];answer_f1=[];evidence_f1=[];em=[];valid=[];answerability=[];states=[]
            pred_official={}; gold_official={}
            for g in examples:
                c=inputs[g['id']];r=rows.get((g['id'],variant),{'status':'missing'})
                selected_rows.append(r);p=parsed(r,c);ok=r['status']=='succeeded'
                rec={'id':g['id'],'dataset':name,'variant':variant,'status':r['status'],'prediction':p}
                valid.append(int(ok and p['format_valid']));states.append(r['status'])
                if name=='SciFact':
                    glabels.append(g['label']);predlabels.append(p['label']);acc=int(ok and p['label']==g['label']);accuracy.append(acc)
                    ef=max(ev.paragraph_f1_score(p['evidence_ids'],e) for e in g['evidence_sets']) if ok and p['format_valid'] else 0
                    evidence_f1.append(ef);rec.update(label_correct=acc,evidence_f1=ef)
                else:
                    references=g['references'];qid=g['question_id']
                    lookup={p['id']:p['text'] for p in g['all_paragraphs']}
                    # Official scoring uses exact paragraph text; IDs only serve as the output adapter.
                    predtexts=[lookup[i] for i in p['evidence_ids']]
                    af=max(ev.token_f1_score(p['answer'],ref['answer']) for ref in references) if ok else 0
                    ef=max(ev.paragraph_f1_score(predtexts,ref['evidence']) for ref in references) if ok and p['answer'] else 0
                    exact=int(ok and bool(p['answer']) and any(ev.normalize_answer(p['answer'])==ev.normalize_answer(ref['answer']) for ref in references))
                    gold_unanswerable=references[0]['type']=='none';pred_unanswerable=ev.normalize_answer(p['answer'])=='unanswerable'
                    aa=int(ok and bool(p['answer']) and gold_unanswerable==pred_unanswerable)
                    answer_f1.append(af);evidence_f1.append(ef);em.append(exact);answerability.append(aa)
                    gold_official[qid]=references
                    if ok and p['answer']:pred_official[qid]={'answer':p['answer'],'evidence':predtexts}
                    rec.update(answer_f1=af,evidence_f1=ef,exact_match=exact,answerability_correct=aa,answer_type=references[0]['type'])
                records.append(rec)
            metrics={'output_success':sum(s=='succeeded' for s in states),'format_valid':sum(valid),'failure_counts':dict(collections.Counter(s for s in states if s!='succeeded')),
                     'evidence_f1':mean(evidence_f1),'mean_generation_calls':mean([r.get('model_calls',0) for r in selected_rows]),
                     'total_tokens':sum(r.get('tokens',0) for r in selected_rows),'mean_latency_seconds':mean([r.get('latency_ms',0)/1000 for r in selected_rows])}
            if name=='SciFact':
                metrics.update(accuracy=mean(accuracy),macro_f1=macro_f1(glabels,predlabels,['SUPPORT','CONTRADICT','NOT_ENOUGH_INFO']),
                    evidence_f1_support_or_contradict=mean([ef for ef,gl in zip(evidence_f1,glabels) if gl!='NOT_ENOUGH_INFO']),
                    confusion={l:dict(collections.Counter(p for g,p in zip(glabels,predlabels) if g==l)) for l in ['SUPPORT','CONTRADICT','NOT_ENOUGH_INFO']})
                scores[(name,variant)]=accuracy
            else:
                official=ev.evaluate(gold_official,pred_official)
                assert abs(official['Answer F1']-mean(answer_f1))<1e-9
                assert abs(official['Evidence F1']-mean(evidence_f1))<1e-9
                metrics.update(answer_f1=mean(answer_f1),exact_match=mean(em),answerability_accuracy=mean(answerability),official_evaluator=official)
                scores[(name,variant)]=answer_f1
            result['variants'][variant]=metrics
        result['paired_feedback_minus_baseline']=paired_interval(scores[(name,'feedback')],scores[(name,'baseline')])
        if name=='QASPER':
            coverage=[];allcoverage=[];hit_ids=[];miss_ids=[]
            for g in examples:
                if g['references'][0]['type']=='none':continue
                ids={p['id'] for p in inputs[g['id']]['paragraphs']}
                goldsets=[set(r['evidence_ids']) for r in g['references']]
                hit=any(ids&s for s in goldsets)
                coverage.append(int(hit));allcoverage.append(int(any(s and s<=ids for s in goldsets)))
                (hit_ids if hit else miss_ids).append(g['id'])
            result['retrieval']={'answerable_questions':len(coverage),'any_gold_evidence_hit':mean(coverage),'complete_gold_evidence_hit':mean(allcoverage)}
            result['retrieval']['generation_by_retrieval_hit']={}
            for variant in ['baseline','constrained','feedback']:
                rr={r['id']:r for r in records if r['dataset']=='QASPER' and r['variant']==variant}
                result['retrieval']['generation_by_retrieval_hit'][variant]={
                  'hit_n':len(hit_ids),'hit_answer_f1':mean([rr[i]['answer_f1'] for i in hit_ids]),
                  'miss_n':len(miss_ids),'miss_answer_f1':mean([rr[i]['answer_f1'] for i in miss_ids])}
        results[name]=result
    (root/'scored-observations.json').write_text(json.dumps(records,ensure_ascii=False,indent=2),encoding='utf-8')
    (root/'results.json').write_text(json.dumps(results,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(results,ensure_ascii=False,indent=2))

if __name__=='__main__':
    ap=argparse.ArgumentParser();ap.add_argument('root',type=Path);score(ap.parse_args().root)
