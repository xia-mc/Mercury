"""
Headless benchmark runner for benchmark:benchmark_macro.
Measures JIT on vs off timing by reading std:vm s3.text after each run.
"""
import shutil
import socket
import struct
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RUN_DIR = ROOT / "run"
WORLD_DATAPACKS = RUN_DIR / "world" / "datapacks"
BENCHMARK_ZIP = RUN_DIR / "saves" / "New World" / "datapacks" / "benchmark_macro.zip"
BENCHMARK_ZIP_DEST = WORLD_DATAPACKS / "benchmark_macro.zip"
LOG_PATH = ROOT / "run" / "server-run.log"
RCON_HOST = "127.0.0.1"
RCON_PORT = 25575
RCON_PASSWORD = "mercury_test"


def rcon_packet(request_id: int, packet_type: int, body: str) -> bytes:
    payload = struct.pack("<ii", request_id, packet_type) + body.encode("utf-8") + b"\x00\x00"
    return struct.pack("<i", len(payload)) + payload


def rcon_recv(sock: socket.socket) -> tuple[int, int, str]:
    header = sock.recv(4)
    if not header:
        raise RuntimeError("No RCON response received")
    (length,) = struct.unpack("<i", header)
    data = b""
    while len(data) < length:
        chunk = sock.recv(length - len(data))
        if not chunk:
            raise RuntimeError("Connection closed during RCON recv")
        data += chunk
    request_id, packet_type = struct.unpack("<ii", data[:8])
    body = data[8:-2].decode("utf-8", errors="replace")
    return request_id, packet_type, body


class RconClient:
    def __init__(self, host: str, port: int, password: str):
        self.host = host
        self.port = port
        self.password = password
        self.sock: socket.socket | None = None
        self.next_id = 1

    def connect(self) -> None:
        self.sock = socket.create_connection((self.host, self.port), timeout=10)
        self.sock.sendall(rcon_packet(self.next_id, 3, self.password))
        self.next_id += 1
        rcon_recv(self.sock)

    def command(self, body: str) -> str:
        assert self.sock is not None
        self.sock.sendall(rcon_packet(self.next_id, 2, body))
        self.next_id += 1
        _, _, response = rcon_recv(self.sock)
        return response

    def close(self) -> None:
        if self.sock is not None:
            self.sock.close()
            self.sock = None


def start_server() -> subprocess.Popen[str]:
    if LOG_PATH.exists():
        try:
            LOG_PATH.unlink()
        except PermissionError:
            pass
    log_handle = LOG_PATH.open("w", encoding="utf-8", buffering=1)
    return subprocess.Popen(
        ["cmd.exe", "/c", ".\\gradlew.bat runServer --console=plain"],
        cwd=ROOT,
        stdout=log_handle,
        stderr=subprocess.STDOUT,
        text=True,
    )


def wait_for_rcon(timeout_seconds: float = 180.0) -> None:
    deadline = time.time() + timeout_seconds
    while time.time() < deadline:
        if LOG_PATH.exists():
            text = LOG_PATH.read_text(encoding="utf-8", errors="replace")
            if "RCON running on" in text:
                return
        time.sleep(1.0)
    raise TimeoutError("Timed out waiting for server RCON startup")


def read_timing(client: RconClient) -> int | None:
    """Read benchmark timing from std:vm s3.text. Returns nanoseconds or None."""
    resp = client.command("data get storage std:vm s3.text")
    # Expected: "Storage std:vm has the following contents: \"31856900\""
    if '"' in resp:
        try:
            start = resp.index('"') + 1
            end = resp.rindex('"')
            val_str = resp[start:end].strip()
            return int(val_str)
        except (ValueError, IndexError):
            return None
    return None


def run_benchmark_passes(client: RconClient, n: int, label: str) -> list[int]:
    """Run benchmark n times, collect timing values."""
    results = []
    for i in range(n):
        client.command("function benchmark:benchmark_macro")
        ns = read_timing(client)
        if ns is not None:
            results.append(ns)
            print(f"  {label} pass {i+1}: {ns:,} ns ({ns/1e6:.1f} ms)")
        else:
            print(f"  {label} pass {i+1}: (no timing)")
    return results


def stop_server(client: RconClient, process: subprocess.Popen[str]) -> None:
    try:
        client.command("stop")
    except Exception:
        pass
    finally:
        client.close()
        try:
            process.wait(timeout=30)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=10)


def avg(values: list[int]) -> float:
    return sum(values) / len(values) if values else 0.0


def main() -> int:
    if not BENCHMARK_ZIP.exists():
        print(f"Benchmark zip not found: {BENCHMARK_ZIP}", file=sys.stderr)
        return 1

    WORLD_DATAPACKS.mkdir(parents=True, exist_ok=True)
    shutil.copy2(BENCHMARK_ZIP, BENCHMARK_ZIP_DEST)
    print(f"Installed benchmark datapack")

    process = start_server()
    client = RconClient(RCON_HOST, RCON_PORT, RCON_PASSWORD)
    try:
        print("Waiting for server to start...")
        wait_for_rcon()
        time.sleep(3.0)
        client.connect()
        print("Connected via RCON\n")

        client.command("gamerule maxCommandChainLength 2147483647")

        # Enable JIT and reload datapacks
        print("Enabling Mercury JIT (triggers reload)...")
        client.command("mercury jit enable")
        time.sleep(5.0)  # wait for JIT compilation

        # Initialize VM
        client.command("function std:__init__")

        # Pass 1: VM initializes itself
        print("Pass 1 (VM init)...")
        client.command("function benchmark:benchmark_macro")
        sbp = client.command("scoreboard players get sbp vm_regs")
        print(f"  After init: {sbp.strip()}")

        # Passes 2-4: JIT warm-up
        print("\nJIT warm-up (2 passes)...")
        run_benchmark_passes(client, 2, "warmup")

        # Passes 5-8: JIT on measurement
        print("\nMeasuring JIT ON (4 passes)...")
        jit_on_results = run_benchmark_passes(client, 4, "JIT-on")

        # Check stats
        stats = client.command("mercury stats")
        print(f"\nMercury stats: {stats}")

        # Disable JIT
        print("\nDisabling JIT...")
        client.command("mercury jit disable")
        time.sleep(5.0)  # wait for reload

        # Re-init VM (reload resets datapack context)
        client.command("gamerule maxCommandChainLength 2147483647")
        client.command("function std:__init__")

        # Pass 1: VM reinit (sbp was reset by std:__init__)
        print("Pass 1 (VM reinit)...")
        client.command("function benchmark:benchmark_macro")
        sbp2 = client.command("scoreboard players get sbp vm_regs")
        print(f"  After reinit: {sbp2.strip()}")

        # JIT off warm-up
        print("\nJIT off warm-up (2 passes)...")
        run_benchmark_passes(client, 2, "warmup-nojit")

        # JIT off measurement
        print("\nMeasuring JIT OFF (4 passes)...")
        jit_off_results = run_benchmark_passes(client, 4, "JIT-off")

        stop_server(client, process)

        # Print summary
        print("\n" + "="*50)
        print("BENCHMARK RESULTS (benchmark:benchmark_macro)")
        print("="*50)
        if jit_on_results and jit_off_results:
            avg_on = avg(jit_on_results)
            avg_off = avg(jit_off_results)
            med_on = sorted(jit_on_results)[len(jit_on_results)//2]
            med_off = sorted(jit_off_results)[len(jit_off_results)//2]
            ratio_avg = avg_off / avg_on if avg_on > 0 else 0
            ratio_med = med_off / med_on if med_on > 0 else 0
            print(f"JIT on  avg: {avg_on/1e6:.1f} ms  median: {med_on/1e6:.1f} ms")
            print(f"JIT off avg: {avg_off/1e6:.1f} ms  median: {med_off/1e6:.1f} ms")
            print(f"Speedup (avg):    {ratio_avg:.2f}x")
            print(f"Speedup (median): {ratio_med:.2f}x")
        else:
            print("Insufficient data for comparison")

        return 0
    except Exception as e:
        print(f"Error: {e}", file=sys.stderr)
        import traceback
        traceback.print_exc()
        try:
            client.close()
        except Exception:
            pass
        process.kill()
        process.wait(timeout=10)
        return 1
    finally:
        if BENCHMARK_ZIP_DEST.exists():
            BENCHMARK_ZIP_DEST.unlink()
            print("\nRemoved benchmark datapack")


if __name__ == "__main__":
    sys.exit(main())
