import ast
import builtins
import contextlib
import importlib
import io
import os
import sys
import time
import traceback
from pathlib import Path


class PixelPyStopped(Exception):
    pass


_repl_namespaces = {}


def analyze(source, filename="main.py"):
    issues = []
    try:
        tree = ast.parse(source, filename=filename)
    except SyntaxError as error:
        return [f"ERROR|{error.lineno or 1}|{error.msg}"]
    imported = {}
    used = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            for name in node.names:
                imported[name.asname or name.name.split('.')[0]] = node.lineno
        elif isinstance(node, ast.ImportFrom):
            for name in node.names:
                imported[name.asname or name.name] = node.lineno
        elif isinstance(node, ast.Name) and isinstance(node.ctx, ast.Load):
            used.add(node.id)
    for name, line in imported.items():
        if name not in used and name != "*":
            issues.append(f"AVISO|{line}|Import '{name}' no utilizado")
    return issues


def repl_eval(source, working_dir=""):
    work = Path(working_dir).resolve() if working_dir else Path.cwd().resolve()
    namespace = _repl_namespaces.setdefault(
        str(work), {"__name__": "__pixelpy_repl__", "__builtins__": builtins}
    )
    output = io.StringIO()
    ok = True
    try:
        with contextlib.redirect_stdout(output), contextlib.redirect_stderr(output):
            try:
                result = eval(compile(source, "<repl>", "eval"), namespace, namespace)
                if result is not None:
                    print(repr(result))
            except SyntaxError:
                exec(compile(source, "<repl>", "exec"), namespace, namespace)
    except BaseException:
        ok = False
        output.write(traceback.format_exc())
    return {"ok": ok, "output": output.getvalue()}


def _clear_project_modules(work):
    """Remove only imported modules whose files are inside this project."""
    importlib.invalidate_caches()
    for name, module in list(sys.modules.items()):
        module_file = getattr(module, "__file__", None)
        if not module_file:
            continue
        try:
            module_path = Path(module_file).resolve()
            if module_path.is_relative_to(work):
                cached_file = getattr(module, "__cached__", None)
                if cached_file:
                    cached_path = Path(cached_file).resolve()
                    if cached_path.is_relative_to(work):
                        cached_path.unlink(missing_ok=True)
                sys.modules.pop(name, None)
        except (OSError, RuntimeError, ValueError):
            continue


def _error_details(error, filename):
    extracted = traceback.extract_tb(error.__traceback__)
    relevant = next((entry for entry in reversed(extracted) if entry.filename == filename), None)
    return {
        "error_file": relevant.filename if relevant else filename,
        "error_line": relevant.lineno if relevant else 0,
        "error_type": type(error).__name__,
        "error_message": str(error),
    }


def execute(
    source,
    inputs="",
    working_dir="",
    input_bridge=None,
    timeout_seconds=120,
    debug=False,
    filename="main.py",
):
    output = io.StringIO()
    old_cwd = os.getcwd()
    old_path = list(sys.path)
    old_trace = sys.gettrace()
    original_input = builtins.input
    original_access = os.access
    work = Path(working_dir).resolve() if working_dir else Path(old_cwd).resolve()
    safe_filename = Path(filename or "main.py").name
    script_path = work / safe_filename
    before = {}
    generated = []
    debug_trace = []
    details = {"error_file": "", "error_line": 0, "error_type": "", "error_message": ""}
    ok = True

    values = iter(inputs.splitlines())

    def android_safe_access(path, mode, *args, **kwargs):
        normalized = os.path.abspath(os.fspath(path))
        if mode & os.W_OK and normalized.startswith("/storage/emulated/0/"):
            return False
        return original_access(path, mode, *args, **kwargs)

    def mobile_input(prompt=""):
        print(prompt, end="")
        if input_bridge is not None:
            value = str(input_bridge.request(prompt))
            print(value)
            return value
        try:
            value = next(values)
        except StopIteration:
            raise EOFError("Falta una entrada. Agrégala en la caja Entrada.")
        print(value)
        return value

    namespace = {
        "__name__": "__main__",
        "__file__": str(script_path),
        "__builtins__": builtins,
    }
    deadline = time.monotonic() + max(5, int(timeout_seconds))
    trace_events = 0

    def trace_execution(frame, event, arg):
        nonlocal trace_events
        trace_events += 1
        if trace_events % 100:
            if (
                debug
                and event == "line"
                and frame.f_code.co_filename == safe_filename
                and len(debug_trace) < 250
            ):
                visible = {
                    key: repr(value)[:80]
                    for key, value in frame.f_locals.items()
                    if not key.startswith("__")
                }
                debug_trace.append(f"L{frame.f_lineno}  {visible}")
            return trace_execution
        if input_bridge is not None and input_bridge.isCancelled():
            raise PixelPyStopped("Ejecución detenida por el usuario")
        if time.monotonic() > deadline:
            raise PixelPyStopped(
                f"La ejecución superó el límite de {timeout_seconds} segundos"
            )
        return trace_execution

    try:
        work.mkdir(parents=True, exist_ok=True)
        before = {
            str(path): path.stat().st_mtime_ns
            for path in work.rglob("*")
            if path.is_file()
        }
        _clear_project_modules(work)
        os.chdir(work)
        if str(work) not in sys.path:
            sys.path.insert(0, str(work))
        builtins.input = mobile_input
        os.access = android_safe_access
        with contextlib.redirect_stdout(output), contextlib.redirect_stderr(output):
            sys.settrace(trace_execution)
            exec(compile(source, safe_filename, "exec"), namespace, namespace)
    except PixelPyStopped as error:
        ok = False
        details.update(_error_details(error, safe_filename))
        output.write(f"\n■ {error}.\n")
    except SystemExit as error:
        exit_code = error.code
        if exit_code not in (None, 0):
            ok = False
            details.update(_error_details(error, safe_filename))
            output.write(f"\n■ El programa terminó con código {exit_code}.\n")
    except EOFError as error:
        ok = False
        details.update(_error_details(error, safe_filename))
        output.write(f"\n⚠ {error}\n")
        output.write("Python solicitó más valores de entrada.\n")
    except BaseException as error:
        ok = False
        details.update(_error_details(error, safe_filename))
        output.write(traceback.format_exc())
    finally:
        builtins.input = original_input
        os.access = original_access
        os.chdir(old_cwd)
        sys.path[:] = old_path
        sys.settrace(old_trace)

    for path in work.rglob("*"):
        if path.is_file() and path.suffix != ".py":
            previous = before.get(str(path))
            if previous is None or path.stat().st_mtime_ns != previous:
                generated.append(str(path))

    return {
        "ok": ok,
        "output": output.getvalue(),
        "files": generated,
        "trace": debug_trace,
        **details,
    }
