import { useEffect, useRef, useState } from "react";
import { attachment, restxopFetch, type AttachmentHandle } from "restxop-js";

const SERVER = new URLSearchParams(location.search).get("server") ?? "http://localhost:18080";
const SIZE = new URLSearchParams(location.search).get("size") ?? "8388608";

interface DocumentPayload {
  title: string;
  author: string;
  pages: number;
  created: string;
  status: string;
  tags: string[];
  sizeBytes: number;
  data: AttachmentHandle;
}

interface Timing {
  payloadMs?: number;
  completedMs?: number;
}

async function sha256Hex(blob: Blob): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", await blob.arrayBuffer());
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

interface UploadEcho {
  label: string;
  size: number;
  sha256: string;
}

/** Upload form: one restxop message carrying metadata plus the file. */
function UploadPanel() {
  const [file, setFile] = useState<File | null>(null);
  const [busy, setBusy] = useState(false);
  const [echo, setEcho] = useState<UploadEcho | null>(null);
  const [localSha, setLocalSha] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function upload() {
    if (!file) return;
    setBusy(true);
    setEcho(null);
    setError(null);
    try {
      const [response, digest] = await Promise.all([
        restxopFetch.post(`${SERVER}/upload`, {
          label: file.name,
          data: attachment(file),
        }),
        sha256Hex(file),
      ]);
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      setEcho((await response.json()) as UploadEcho);
      setLocalSha(digest);
    } catch (err) {
      setError(String(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="panel">
      <h2>Upload</h2>
      <p>Send a file together with metadata as one restxop message.</p>
      <input
        type="file"
        data-testid="upload-file"
        onChange={(e) => setFile(e.target.files?.[0] ?? null)}
      />
      <button type="button" data-testid="upload-go" disabled={!file || busy} onClick={upload}>
        {busy ? "uploading…" : "upload"}
      </button>
      {error && <p role="alert">Upload failed: {error}</p>}
      {echo && (
        <dl>
          <dt>Echoed label</dt>
          <dd data-testid="echo-label">{echo.label}</dd>
          <dt>Echoed size</dt>
          <dd data-testid="echo-size">{echo.size}</dd>
          <dt>Echoed SHA-256</dt>
          <dd data-testid="echo-sha">{echo.sha256}</dd>
          <dt>Matches local digest</dt>
          <dd data-testid="echo-match">{String(echo.sha256 === localSha)}</dd>
        </dl>
      )}
    </div>
  );
}

export function App() {
  const [payload, setPayload] = useState<DocumentPayload | null>(null);
  const [received, setReceived] = useState(0);
  const [pdfUrl, setPdfUrl] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [sha, setSha] = useState<string | null>(null);
  const [timing, setTiming] = useState<Timing>({});
  const started = useRef(false);

  useEffect(() => {
    if (started.current) return; // StrictMode double-invoke guard
    started.current = true;

    const start = performance.now();
    (async () => {
      // ONE request: the typed payload resolves as soon as the root part
      // arrives; the PDF is still streaming behind it
      const message = await restxopFetch<DocumentPayload>(`${SERVER}/document?size=${SIZE}`);
      setPayload(message.payload);
      setTiming({ payloadMs: Math.round(performance.now() - start) });

      const reader = message.payload.data.stream().getReader();
      const chunks: BlobPart[] = [];
      let total = 0;
      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        chunks.push(value as BlobPart);
        total += value.length;
        setReceived(total);
      }
      setTiming((t) => ({ ...t, completedMs: Math.round(performance.now() - start) }));
      const blob = new Blob(chunks, { type: "application/pdf" });
      setPdfUrl(URL.createObjectURL(blob));
      setSha(await sha256Hex(blob));
      await message.completed;
    })().catch((err: unknown) => setError(String(err)));
  }, []);

  if (error) return <p role="alert">Failed: {error}</p>;

  return (
    <div>
      <h1>restxop streaming demo</h1>
      <p>
        A single <code>multipart/related</code> request: the metadata below rendered the moment
        the JSON root part arrived, while the PDF attachment was still streaming in.
      </p>
      <div className="layout">
        <div className="panel" data-testid="metadata">
          <h2>Document metadata</h2>
          {!payload && <p>Requesting…</p>}
          {payload && (
            <>
              <dl>
                <dt>Title</dt>
                <dd data-testid="title">{payload.title}</dd>
                <dt>Author</dt>
                <dd>{payload.author}</dd>
                <dt>Created</dt>
                <dd>{new Date(payload.created).toLocaleString()}</dd>
                <dt>Status</dt>
                <dd>{payload.status}</dd>
                <dt>Pages</dt>
                <dd>{payload.pages}</dd>
                <dt>Tags</dt>
                <dd>
                  {payload.tags.map((tag) => (
                    <span className="tag" key={tag}>
                      {tag}
                    </span>
                  ))}
                </dd>
                <dt>Size</dt>
                <dd>{(payload.sizeBytes / 1024 / 1024).toFixed(1)} MB</dd>
              </dl>
              <p className="stat" data-testid="payload-at">
                payload available at {timing.payloadMs} ms
              </p>
            </>
          )}
        </div>
        <div className="panel">
          <h2>{payload?.data.filename ?? "Document"}</h2>
          {payload && !pdfUrl && (
            <>
              <p data-testid="streaming">attachment streaming…</p>
              <progress value={received} max={payload.sizeBytes} />
              <p className="stat">
                {(received / 1024 / 1024).toFixed(1)} / {(payload.sizeBytes / 1024 / 1024).toFixed(1)} MB
              </p>
            </>
          )}
          {pdfUrl && (
            <>
              <p className="stat" data-testid="completed-at">
                transfer complete at {timing.completedMs} ms
              </p>
              {sha && (
                <p className="stat">
                  sha256 <span data-testid="sha256">{sha}</span>
                </p>
              )}
              <iframe title="document" src={pdfUrl} data-testid="pdf-frame" />
            </>
          )}
        </div>
        <UploadPanel />
      </div>
    </div>
  );
}
