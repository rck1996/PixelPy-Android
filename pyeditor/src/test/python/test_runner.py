import builtins
import os
import sys
import tempfile
import unittest
from pathlib import Path


PYTHON_DIR = Path(__file__).resolve().parents[2] / "main" / "python"
sys.path.insert(0, str(PYTHON_DIR))
import runner


class Bridge:
    def __init__(self, cancelled=False, fail=False):
        self.cancelled = cancelled
        self.fail = fail
        self.prompts = []

    def request(self, prompt):
        self.prompts.append(prompt)
        return "respuesta"

    def isCancelled(self):
        if self.fail:
            raise RuntimeError("bridge failure")
        return self.cancelled


class RunnerTests(unittest.TestCase):
    def execute(self, source, filename="main.py", bridge=None, timeout=120, debug=False):
        return runner.execute(
            source,
            working_dir=self.temp.name,
            input_bridge=bridge,
            timeout_seconds=timeout,
            debug=debug,
            filename=filename,
        )

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()

    def tearDown(self):
        self.temp.cleanup()

    def test_input_bridge_request_is_accessible(self):
        bridge = Bridge()
        result = self.execute('print(input("Dato: "))', bridge=bridge)
        self.assertTrue(result["ok"])
        self.assertEqual(["Dato: "], bridge.prompts)
        self.assertIn("respuesta", result["output"])

    def test_cancellation_and_timeout(self):
        cancelled = self.execute("while True: pass", bridge=Bridge(cancelled=True))
        self.assertFalse(cancelled["ok"])
        self.assertIn("detenida", cancelled["output"])
        cwd = os.getcwd()
        path = list(sys.path)
        input_function = builtins.input
        access_function = os.access
        trace_function = sys.gettrace()
        timed_out = self.execute("while True: pass", timeout=0)
        self.assertFalse(timed_out["ok"])
        self.assertIn("límite", timed_out["output"])
        self.assertEqual(cwd, os.getcwd())
        self.assertEqual(path, sys.path)
        self.assertIs(input_function, builtins.input)
        self.assertIs(access_function, os.access)
        self.assertIs(trace_function, sys.gettrace())

    def test_system_exit_zero_and_nonzero(self):
        zero = self.execute("import sys\nsys.exit(0)")
        self.assertTrue(zero["ok"])
        self.assertNotIn("Traceback", zero["output"])
        nonzero = self.execute("import sys\nsys.exit(7)")
        self.assertFalse(nonzero["ok"])
        self.assertIn("código 7", nonzero["output"])
        self.assertEqual("SystemExit", nonzero["error_type"])

    def test_real_filename_and_error_line(self):
        result = self.execute("x = 1\nraise ValueError('boom')", "archivo_prueba.py")
        self.assertFalse(result["ok"])
        self.assertIn('archivo_prueba.py", line 2', result["output"])
        self.assertEqual("archivo_prueba.py", result["error_file"])
        self.assertEqual(2, result["error_line"])

    def test_debug_only_records_executed_file(self):
        result = self.execute("x = 1\nx += 1", "depurar.py", debug=True)
        self.assertTrue(result["ok"])
        self.assertTrue(any(line.startswith("L1") for line in result["trace"]))

    def test_global_state_is_restored_for_all_exit_paths(self):
        cases = [
            ("print('ok')", None),
            ("raise ValueError('x')", None),
            ("import sys; sys.exit(0)", None),
            ("while True: pass", Bridge(cancelled=True)),
            ("for _ in range(200):\n    pass", Bridge(fail=True)),
        ]
        for source, bridge in cases:
            with self.subTest(source=source, bridge=type(bridge).__name__):
                cwd = os.getcwd()
                path = list(sys.path)
                input_function = builtins.input
                access_function = os.access
                trace_function = sys.gettrace()
                self.execute(source, bridge=bridge)
                self.assertEqual(cwd, os.getcwd())
                self.assertEqual(path, sys.path)
                self.assertIs(input_function, builtins.input)
                self.assertIs(access_function, os.access)
                self.assertIs(trace_function, sys.gettrace())

    def test_cancel_check_failure_has_clear_internal_diagnostic(self):
        result = self.execute(
            "for _ in range(200):\n    pass\nraise ValueError('script failure')",
            bridge=Bridge(fail=True),
        )
        self.assertFalse(result["ok"])
        self.assertEqual("ValueError", result["error_type"])
        self.assertIn("script failure", result["output"])
        self.assertIn("DIAGNÓSTICO INTERNO DEL PUENTE", result["output"])
        self.assertIn("bridge failure", result["output"])

    def test_local_module_is_reloaded_but_external_module_is_preserved(self):
        module = Path(self.temp.name) / "utilidades.py"
        module.write_text("VALUE = 1\n", encoding="utf-8")
        first = self.execute("import utilidades\nprint(utilidades.VALUE)")
        import json
        external_json = sys.modules["json"]
        module.write_text("VALUE = 2\n", encoding="utf-8")
        second = self.execute("import utilidades\nprint(utilidades.VALUE)")
        self.assertEqual("1", first["output"].strip())
        self.assertEqual("2", second["output"].strip())
        self.assertIs(external_json, sys.modules["json"])

    def test_analyze_keeps_backward_compatible_default(self):
        self.assertEqual([], runner.analyze("x = 1"))
        issue = runner.analyze("if:", "nombre_real.py")[0]
        self.assertTrue(issue.startswith("ERROR|1|"))


if __name__ == "__main__":
    unittest.main()
