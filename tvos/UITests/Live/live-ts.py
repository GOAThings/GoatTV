#!/usr/bin/env python3
"""Um canal .ts "ao vivo" para o teste da pausa do direto (.github/workflows/timeshift-sim.yml).

Uso: live-ts.py <ficheiro.ts> <porta> <pasta> <duração em s>
Dá o ficheiro em ciclo, ao ritmo real, a quem pedir /live.ts (como um fornecedor IPTV: começa no "direto" e nunca
acaba). Conta as ligações ao mesmo tempo, porque o fornecedor só deixa uma: o máximo fica em <pasta>/max-connections.txt
e cada ligação em <pasta>/server.log.
"""
import os, sys, threading, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

path, port, outdir, duration = sys.argv[1], int(sys.argv[2]), sys.argv[3], float(sys.argv[4])
data = open(path, "rb").read()
rate = len(data) / duration
start = time.time()
lock = threading.Lock()
state = {"active": 0, "peak": 0, "total": 0}


def note(line):
    print(time.strftime("%H:%M:%S"), line, flush=True)


def save_peak():
    with open(os.path.join(outdir, "max-connections.txt"), "w") as f:
        f.write(str(state["peak"]))


save_peak()


class Live(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path.split("?")[0] != "/live.ts":
            self.send_error(404)
            return
        with lock:
            state["active"] += 1
            state["total"] += 1
            state["peak"] = max(state["peak"], state["active"])
            save_peak()
            note(f"open: {state['active']} at once, {state['total']} so far, agent {self.headers.get('User-Agent')}")
        try:
            self.send_response(200)
            self.send_header("Content-Type", "video/mp2t")
            self.end_headers()
            # Começa onde o "direto" vai, num início de pacote.
            pos = int(((time.time() - start) * rate) % len(data)) // 188 * 188
            began = time.time()
            sent = 0
            while True:
                end = min(pos + 188 * 64, len(data))
                self.wfile.write(data[pos:end])
                sent += end - pos
                pos = end % len(data)
                ahead = sent / rate - (time.time() - began)
                if ahead > 0:
                    time.sleep(ahead)
        except (BrokenPipeError, ConnectionResetError):
            pass
        finally:
            with lock:
                state["active"] -= 1
                note(f"closed: {state['active']} at once")

    def log_message(self, *args):
        pass


ThreadingHTTPServer(("127.0.0.1", port), Live).serve_forever()
