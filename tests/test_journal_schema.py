"""Exercise SQL used by the Android journal against real SQLite files."""
import re
import sqlite3
import tempfile
import unittest
from pathlib import Path

SOURCE = Path(__file__).resolve().parents[1] / 'android/app/src/main/java/com/veluvara/translatorlab/DialogueStore.java'

class JournalSchemaTests(unittest.TestCase):
    def test_persistence_order_pagination_and_session_deletion(self):
        code = SOURCE.read_text()
        schema = re.findall(r'db.execSQL\("(CREATE [^"]+)"\)', code)
        query = re.search(r'rawQuery\("([^"]+)"', code).group(1)
        with tempfile.TemporaryDirectory() as directory:
            filename = Path(directory) / 'dialogues.db'
            with sqlite3.connect(filename) as db:
                for sql in schema:
                    db.execute(sql)
                for i in range(53):
                    db.execute('INSERT INTO phrases(session,request,created,source_lang,target_lang,original,translated) VALUES(?,?,?,?,?,?,?)',
                               ('first' if i < 3 else 'second', str(i), i, 'de-DE', 'ru-RU', 'Grüße! «ä»', 'Здравствуйте!'))
                db.execute("INSERT OR IGNORE INTO phrases(session,request,created,source_lang,target_lang,original,translated) SELECT session,request,created,source_lang,target_lang,original,translated FROM phrases WHERE id=1")
                self.assertEqual(db.execute('SELECT count(*) FROM phrases').fetchone()[0], 53)
            with sqlite3.connect(filename) as db:
                page = db.execute(query, (2**63-1,)).fetchall()
                self.assertEqual(len(page), 50)
                self.assertEqual(page[0][5], 'Grüße! «ä»')
                self.assertEqual(page[0][6], 'Здравствуйте!')
                older = db.execute(query, (page[-1][0],)).fetchall()
                self.assertEqual(len(older), 3)
                self.assertFalse(set(row[0] for row in page) & set(row[0] for row in older))
                db.execute('DELETE FROM phrases WHERE session = ?', ('first',))
                self.assertEqual(db.execute('SELECT count(*) FROM phrases').fetchone()[0], 50)
                db.execute('DELETE FROM phrases')
                self.assertEqual(db.execute('SELECT count(*) FROM phrases').fetchone()[0], 0)
