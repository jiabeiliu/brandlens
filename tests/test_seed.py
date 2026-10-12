import importlib.util,json,tempfile,unittest
from pathlib import Path
from unittest.mock import patch
spec=importlib.util.spec_from_file_location('seed',Path(__file__).parents[1]/'scripts/seed.py'); seed=importlib.util.module_from_spec(spec);spec.loader.exec_module(seed)
class SeedMigrationTest(unittest.TestCase):
 def run_migration(self,fail):
  with tempfile.TemporaryDirectory() as d:
   root=Path(d); (root/'nike').mkdir(); (root/'nike'/'new.txt').write_text('Sourced replacement '*20)
   state=root/'state.json';state.write_text(json.dumps({'docs':{'nike-old':'oldhash'}}));deleted=[]
   def post(*args):
    if fail:raise OSError('embedding unavailable')
    return {'chunks':1}
   with patch.object(seed,'DATA_ROOT',root),patch.object(seed,'STATE_FILE',state),patch.object(seed,'post',side_effect=post),patch.object(seed,'delete',side_effect=lambda path: deleted.append(path)),patch('sys.argv',['seed.py']):
    result=seed.main()
   return result,deleted,json.loads(state.read_text())['docs']
 def test_failed_replacement_preserves_known_old_document(self):
  result,deleted,state=self.run_migration(True);self.assertEqual(1,result);self.assertEqual([],deleted);self.assertIn('nike-old',state)
 def test_successful_replacement_reconciles_known_removed_document(self):
  result,deleted,state=self.run_migration(False);self.assertEqual(0,result);self.assertEqual(['/api/ingest/nike/documents/nike-old'],deleted);self.assertNotIn('nike-old',state)
