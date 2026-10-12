import importlib.util,unittest
from pathlib import Path
spec=importlib.util.spec_from_file_location('evaluate',Path(__file__).parents[1]/'scripts/evaluate.py'); module=importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
class EvaluationTest(unittest.TestCase):
 def test_unknown_reference_is_not_grounding(self):
  result=module.checks('Claim [chunk:99]',[{'chunkId':1,'docId':'doc'}]); self.assertEqual(['99'],result['invalid_citations'])
 def test_retrieved_but_unused_is_not_cited(self):
  self.assertFalse(module.checks('Uncited answer',[{'chunkId':1,'docId':'doc'}])['has_citations'])
