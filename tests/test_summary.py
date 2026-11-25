import unittest
from experiment.summary import summarize

class SummaryTests(unittest.TestCase):
    def test_percentiles_and_failures(self):
        rows=[{'success':True,'stt_ms':n} for n in range(1,101)]+[{'success':False}]
        result=summarize(rows)
        self.assertEqual(result['failures'],1)
        self.assertEqual(result['latency']['stt_ms'],{'count':100,'p50':50,'p95':95})
        self.assertIsNone(result['latency']['total_ms']['p50'])
    def test_bad_measurements(self):
        for value in (-1,float('nan'),True,'12'):
            with self.subTest(value=value), self.assertRaises(ValueError):
                summarize([{'success':True,'stt_ms':value}])
    def test_empty(self):
        self.assertEqual(summarize([])['attempts'],0)
