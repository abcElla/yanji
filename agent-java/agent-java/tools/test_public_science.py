import unittest
from prepare_public_science import bm25
from score_public_science import marker, parsed, macro_f1, paired_interval

class PublicScienceEvaluationTests(unittest.TestCase):
    def test_answer_marker_at_end_of_prose(self):
        self.assertEqual(marker('原文直接矛盾。FINAL_LABEL: CONTRADICT\nFINAL_EVIDENCE: s004','FINAL_LABEL'),'CONTRADICT')
    def test_failed_response_has_no_valid_label(self):
        p=parsed({'status':'failed'},{'dataset':'SciFact','paragraphs':[]})
        self.assertEqual(p['label'],'INVALID');self.assertFalse(p['format_valid'])
    def test_evidence_ids_must_come_from_supplied_input(self):
        r={'answer':{'result':{'summary':'FINAL_ANSWER: Yes\nFINAL_EVIDENCE: p001,p001,p999'}}}
        p=parsed(r,{'dataset':'QASPER','paragraphs':[{'id':'p001'}]})
        self.assertEqual(p['evidence_ids'],['p001']);self.assertEqual(p['invalid_evidence_ids'],['p999'])
    def test_bm25_uses_question_and_text_without_gold(self):
        p=[{'id':'p0','text':'unrelated sentence'}, {'id':'p1','text':'training dataset image segmentation'}]
        self.assertEqual(bm25('training dataset',p,k=1)[0]['id'],'p1')
    def test_invalid_prediction_is_not_removed_from_macro_f1(self):
        self.assertAlmostEqual(macro_f1(['S','C','N'],['S','INVALID','N'],['S','C','N']),2/3)
    def test_paired_interval_is_reproducible(self):
        self.assertEqual(paired_interval([1,0],[0,0]),paired_interval([1,0],[0,0]))
    def test_short_answer_preserves_english(self):
        self.assertEqual(marker('说明\nFINAL_ANSWER: The ACL corpus\nFINAL_EVIDENCE: p001','FINAL_ANSWER'),'The ACL corpus')
    def test_equivalent_json_result_position(self):
        r={'answer':{'result':{'summary':'简短解释'},'FINAL_LABEL':'SUPPORT','FINAL_EVIDENCE':['s001']}}
        p=parsed(r,{'dataset':'SciFact','paragraphs':[{'id':'s001'}]})
        self.assertEqual(p['label'],'SUPPORT');self.assertTrue(p['format_valid'])
    def test_joined_marker_line_does_not_change_label(self):
        self.assertEqual(marker('理由。FINAL_LABEL: CONTRADICT。FINAL_EVIDENCE: s001。','FINAL_LABEL'),'CONTRADICT')

if __name__=='__main__':unittest.main()
