<!DOCTYPE html>
<html>
<head>
    <meta http-equiv="content-type" content="text/html; charset=utf-8"/>
    <title>SLiCER - Safe Long-term Cloud Event Archival</title>
</head>
<body lang="en-US" link="#000080" vlink="#800000" dir="ltr">
<h1 class="western">🗡️ SLiCER (Safe Long-term Cloud Event Archival)</h1>
<p><strong>SLiCER</strong> is a cloud event archival engine designed
to operate at the edge. It is capable of ingesting, encrypting,
indexing, and synchronizing thousands of security logs per second,
utilizing a multi-tier architecture that integrates local storage,
mapped single or multi-cloud sync folders (e.g., Google Drive,
Dropbox, <em>Vawlt.io</em> or academic alternative
<em>“Charon”</em>, etc.), and AWS S3.
SLiCER is currently in <strong>Version 2</strong>, with several capabilities improved
and new ones added. The first version, published in PRDC 2019, is also available, but is deprecated.
</p>

<h2 class="western">🚀 Key Features</h2>
<ul>
    <li><p><strong>High-Performance Ingestion:</strong> Asynchronous TCP
    engine and directory spool monitor capable of processing +5,000 EPS without Race Condition
    bottlenecks, utilizing an <em>Atomic State Push</em> pattern.</p></li>
    <li><p><strong>Format Agnostic:</strong> Native parsing support for
    <strong>CEF</strong> (e.g., ArcSight) and <strong>JSON</strong> logs,
    with dynamic device routing via an extensible <code>EventParser</code> interface.</p></li>
    <li><p><strong>Hybrid Indexing &amp; Self-Tuning Bloom Filters:</strong> Combines Apache Lucene inverted indexes (using <code>PerFieldAnalyzerWrapper</code> to preserve punctuation in IPs and device IDs like <code>ids.snort</code>) with O(1) Bloom Filters dynamically sized via a 5-block moving average.</p></li>
    <li><p><strong>Shielded Storage &amp; Blockchain Ledger:</strong> Data and indexes
    are packaged into <code class="western">.tar.xz</code>, chained via SHA-256 hashes (<code class="western">chain.meta</code> + central monthly ledgers),
    and encrypted at rest via <strong>AES-256 GCM</strong> (<code class="western">SLCR</code> envelope) using an
    in-memory Master Password.</p></li>
    <li><p><strong>Multi-Cloud Synchronization (Egress-Drop):</strong>
    Automatic synchronization to mapped cloud folders (Google Drive,
    Dropbox, OneDrive, <em>Vawlt.io</em>, Charon, etc.) and AWS S3, deleting raw
    local data to save space immediately after upload.</p></li>
    <li><p><strong>Distributed Real-Time Search:</strong> Search engine
    powered by Apache Lucene, with NDJSON Streaming support for instant
    UI rendering across device tabs and collapsible block trees.</p></li>
    <li><p><strong>Graceful Shutdown:</strong> Shutdown interception
    ensures open blocks are securely closed and forces a synchronous
    upload to the cloud before the JVM dies, guaranteeing <strong>zero
    data loss</strong>.</p></li>
</ul>

<h2 class="western">📁 Directory Structure and Data Lifecycle</h2>
<p>SLiCER's architecture is based on directories with strictly
defined purposes. Understanding this structure is fundamental for
operating the system and performing forensic analysis.</p>
<pre class="western"><code class="western">/storage
  ├── /ingest_cache           # 1. Hot Tier (Local Buffer and Active Indexes)
  │    └── /&lt;device_name&gt;
  │         ├── /data         # Recent raw logs (newblockRaw.slc &amp; .tar.xz)
  │         ├── /index        # Lucene Indexes + Bloom Filter (.bf)
  │         └── chain_state.txt # Last SHA-256 hash for local block chaining
  ├── /tmp_charon             # 2. Warm Tier (Multi-Cloud / DropDir)
  │    └── /&lt;device_name&gt;
  ├── /exports                # 3. Investigation Tier (Decrypted Data)
  │    └── /&lt;device_name&gt;
/ledgers
  └── /&lt;device_name&gt;          # 4. Immutable Tier (Local Blockchain Manifests)</code></pre>

<h3 class="western">Folder Breakdown:</h3>
<ol>
    <li><p><strong><code class="western">/ingest_cache</code> (Hot Tier)</strong>: Where real-time magic happens. Incoming TCP
    events are buffered here. The engine rotates blocks based on time or
    size limits (e.g., 5MB or 15 mins). It has a strict retention
    policy: once synced, <strong>Egress-Drop</strong> deletes the local raw data archive (<code class="western">data/*.tar.xz</code>), and when the cache limit is reached (e.g., 5GB), older synced indexes are automatically pruned to free up space.</p></li>
    <li><p><strong><code class="western">/tmp_charon</code> (Warm Tier)</strong>: The integration point for multi-cloud storage.
    Although internally referenced as <em>Charon</em>, this is simply a
    local Drop Directory that can be mapped to any cloud sync service
    (like Google Drive, Dropbox, Vawlt.io) or a network NAS. Encrypted blocks
    (<code class="western">.enc</code>) are copied here before AWS S3.
    SLiCER checks this folder for missing blocks before hitting S3,
    resulting in zero egress costs.</p></li>
    <li><p><strong><code class="western">/exports</code> (Investigation Tier)</strong>: When an analyst clicks &quot;Decrypt
    and Export Block&quot; in the Dashboard, SLiCER fetches the
    encrypted block, applies the in-memory AES-256 key, and extracts the
    raw, readable <code class="western">.tar.xz</code> files here, ready for deep forensic analysis.</p></li>
    <li><p><strong><code class="western">/ledgers</code> (Immutable Tier)</strong>: The definitive system memory containing
    <code class="western">manifest_YYYY-MM.json</code> files. Even when
    the <code class="western">/ingest_cache</code> prunes old blocks to
    save space, the SHA-256 hash and block name remain in the Ledger.
    This tells the search engine: <em>&quot;This block exists, but
    you'll have to fetch it from the Warm or Cold tier.&quot;</em></p></li>
</ol>

<h2 class="western">⚙️ System Configuration &amp; Field Selection</h2>
<p>Before running SLiCER, users can customize ports, rotation thresholds, storage paths, and exactly which log fields are indexed by editing the configuration files below.</p>

<h3 class="western">1. Main Configuration &amp; Field Indexing (<code class="western">application.yml</code>)</h3>
<p>SLiCER loads its runtime parameters via <code class="western">AppConfig</code> from your YAML configuration file. While the <strong>raw log is always preserved in full</strong> inside the compressed <code class="western">.slc</code> data archive, you can optimize index size on edge nodes by selecting which parsed fields are indexed into Apache Lucene and the Bloom Filter:</p>
<ul>
    <li><p><strong><code class="western">defaultFieldsToIndex</code></strong>: Defines the fallback list of fields indexed for any device that does not have a specific rule (e.g., <code class="western">["src", "dst", "act", "suser", "dvc"]</code>).</p></li>
    <li><p><strong><code class="western">deviceFields</code></strong>: A map allowing per-device field selection (e.g., indexing <code class="western">query</code> and <code class="western">db</code> specifically for <code class="western">sgbd.mysql</code>).</p></li>
    <li><p><strong>Full Indexing (<code class="western">"all"</code>)</strong>: Including <code class="western">"all"</code> in the field list (or leaving the lists empty) instructs <code class="western">DeviceBlockManager</code> to index every key-value pair extracted by the parser.</p></li>
</ul>
<pre class="western"><code class="western">slicer:
  ingest:
    tcpPort: 5140                           # TCP port for receiving CEF/JSON events
    spoolDir: "storage/spool"               # Monitored directory for batch log files
  api:
    port: 7000                              # Web Dashboard &amp; REST API port
  block:
    maxSizeMb: 5                            # Close block when raw size reaches N MB
    maxIntervalMinutes: 15                  # Close block after N minutes
    bufferLimit: 1000                       # Events in RAM before synchronized disk flush
    defaultFieldsToIndex:
      - "src"
      - "dst"
      - "act"
      - "suser"
      - "dvc"
    deviceFields:
      sgbd.mysql:
        - "suser"
        - "src_ip"
        - "query"
        - "db"
      webserver.php:
        - "all"                             # Indexes all fields for this device
  storage:
    ingestCacheDir: "storage/ingest_cache"  # Hot Tier local directory
    cloudDropDir: "storage/tmp_charon"      # Warm Tier mapped cloud directory ("" to disable)
    maxIngestCacheMb: 5120                  # Max local cache size per device before pruning
    syncIntervalSeconds: 30                 # Background cloud sync frequency
  aws:
    bucketName: "my-slicer-bucket"          # Cold Tier AWS S3 bucket ("" to disable)</code></pre>

<h3 class="western">2. Registering Devices in the Web UI (<code class="western">web/index.html</code>)</h3>
<p>SLiCER routes incoming logs into device-specific folders based on the <code class="western">dvchost</code> or <code class="western">dvc</code> field. To make new devices selectable in the Threat Hunting Dashboard dropdown, add a checkbox entry inside the <code class="western">&lt;div id="checkboxes"&gt;</code> section of <code class="western">web/index.html</code> matching the exact device name:</p>
<pre class="western"><code class="western">&lt;label&gt;&lt;input type="checkbox" class="dev-cb" value="ids.snort" onchange="updateMultiSelectText()" checked/&gt; ids.snort&lt;/label&gt;</code></pre>

<h3 class="western">3. Configuring the Traffic Simulator (<code class="western">simulator.json</code>)</h3>
<p>Located in the project root directory (alongside <code class="western">pom.xml</code>), <code class="western">simulator.json</code> defines the target TCP socket, optional local audit backup (<code class="western">.gz</code>), and the array of simulated devices with their respective Event Per Second (EPS) rates and formats (<code class="western">CEF</code> or <code class="western">JSON</code>):</p>
<pre class="western"><code class="western">{
  "targetHost": "127.0.0.1",
  "targetTcpPort": 5140,
  "saveToDisk": true,
  "diskOutputPath": "storage/mock_data",
  "devices": [
    { "name": "webserver.php", "eps": 719, "format": "JSON" },
    { "name": "sgbd.postgresql", "eps": 749, "format": "CEF" },
    { "name": "sgbd.mysql", "eps": 856, "format": "JSON" },
    { "name": "proxy.squid", "eps": 756, "format": "CEF" },
    { "name": "ids.snort", "eps": 669, "format": "CEF" },
    { "name": "firewall.sonicwall", "eps": 755, "format": "CEF" },
    { "name": "firewall.ciscoasa", "eps": 833, "format": "CEF" }
  ]
}</code></pre>

<h3 class="western">4. Adding Custom Log Parsers (<code class="western">EventParser</code>)</h3>
<p>By default, <code class="western">SlicerDaemon</code> initializes <code class="western">CefParser</code> and <code class="western">JsonParser</code>. To ingest a new log format, implement the <code class="western">br.edu.utfpr.slicer.parser.EventParser</code> interface and add your parser to the <code class="western">List&lt;EventParser&gt; parsers</code> declaration in <code class="western">SlicerDaemon.java</code>.</p>

<h2 class="western">🖥️ Threat Hunting Dashboard</h2>
<p>The Web interface (accessible via <code class="western">http://localhost:7000</code>)
is not just a viewer, but an orchestrator for the decentralized
storage.</p>

<h3 class="western">1. Search Panel &amp; Operation Modes</h3>
<ul>
    <li><p><strong>Device Selector:</strong> Focus searches on specific
    devices or select <code class="western">[Todos]</code>.</p></li>
    <li><p><strong>Simplified vs. Advanced Mode:</strong> Use simplified mode for quick key-value filtering (<code class="western">dst=10.0.0.177, act=drop</code>) accelerated by Bloom Filters, or switch to
    advanced mode to write complex Boolean queries directly in Apache Lucene
    syntax (e.g., <code class="western">dvc:ids.snort OR (dvc:sgbd.mysql AND query:"DROP TABLE logs")</code>).</p></li>
</ul>

<h3 class="western">2. Event Tree-View Behavior</h3>
<p>Results are delivered via <strong>NDJSON Streaming</strong> and
rendered in real-time in separate tabs per device.</p>
<ul>
    <li><p><strong>Cache vs. Cloud:</strong> Expanding a block in the UI
    displays matching events. If the index/block is in
    <code class="western">/ingest_cache</code> (Hot Tier), retrieval is
    instantaneous.</p></li>
    <li><p><strong>Transparent Multi-Tier Fallback:</strong> If the block was already
    pruned locally by the retention policy (existing only in the Ledger),
    SLiCER automatically fetches and decrypts the required archives on-demand from the Warm Tier (Charon/Mapped Drive) or Cold Tier (AWS S3).</p></li>
</ul>

<h3 class="western">3. &quot;S3 Cost&quot; Indicator</h3>
<p>Cloud systems generate egress costs. SLiCER brings <strong>FinOps</strong>
directly to the analyst:</p>
<ul>
    <li><p>During a search, if the system needs to download <code class="western">.tar.xz.enc</code>
    files from AWS S3, the interface calculates the transferred
    megabytes.</p></li>
    <li><p>A badge updates in the top right corner displaying something
    like <strong><code class="western">☁️ Custo S3: ~$0.0945</code></strong>.
    If all data was retrieved from local cache or the mapped Multi-Cloud
    folder, the panel proudly displays <strong><code class="western">☁️
    Custo S3: Zero</code></strong>.</p></li>
</ul>

<h3 class="western">4. Selective Forensic Export</h3>
<p>Unlike legacy systems that download entire daily backups, SLiCER
is surgical:</p>
<ul>
    <li><p><strong>&quot;Download All Listed Blocks&quot; Button:</strong>
    Clicking this button grabs <strong>only the specific block names</strong>
    that matched the current query in that tab (<code class="western">blocksInView</code>) and sends them to <code class="western">POST /api/export/selected</code>.</p></li>
    <li><p><strong>Extraction:</strong> The backend retrieves only those
    specific files (from Local Disk, Drive/Charon, or S3), decrypts
    them, and places the clean <code class="western">.tar.xz</code> archives in <code class="western">/exports/</code>.
    This maximizes bandwidth savings and minimizes cloud costs.</p></li>
</ul>

<h2 class="western">🛠️ Installation and Execution</h2>
<p><strong>Prerequisites:</strong> Java 17+ and Maven 3.8+ (plus standard AWS SDK credentials if S3 is enabled).</p>
<pre class="western"><code class="western">git clone https://github.com/username/slicer.git
cd slicer
mvn clean install</code></pre>
<p><strong>1. Starting SLiCER (Daemon):</strong> Run
<code class="western">br.edu.utfpr.slicer.SlicerDaemon</code>. The
console will pause and prompt for the <strong>Master Password</strong>
(required to derive the AES-256 cryptographic key in RAM). Always use the same password to read existing encrypted blocks!</p>
<p><strong>2. Generating Traffic (Simulator):</strong> Ensure
<code class="western">simulator.json</code> is in the project root.
Run the class <code class="western">br.edu.utfpr.slicer.tools.ThreatSimulator</code>.
It will inject simulated traffic (~5,300 EPS) from Firewalls, DBs,
and Web Servers.</p>
<p><strong>3. Accessing the UI:</strong> Open your browser at <code class="western">http://localhost:7000</code> (do not open <code class="western">index.html</code> via <code class="western">file:///</code> double-click to avoid CORS <code class="western">NetworkError</code>).</p>

<h2 class="western">🧪 Simulating Searches Across Tiers (Local, Charon, S3)</h2>
<p>SLiCER is smart about data retrieval. You can force the system to
fetch logs from different storage tiers by intentionally deleting
local files.</p>
<blockquote><strong>Warning:</strong> Turn off the <code class="western">ThreatSimulator</code>
before testing to prevent new blocks from confusing your analysis.
<strong>NEVER</strong> delete files from <code class="western">/ledgers</code>,
as the system relies on them to track the generated block history.</blockquote>

<h3 class="western">Scenario 1: Hot Tier Search (Local Cache)</h3>
<ol>
    <li><p>Run the simulator for a few minutes and execute a query
    (e.g., <code class="western">dvc:ids.snort</code>).</p></li>
    <li><p>The search will be nearly instantaneous, and the UI will
    display <strong>S3 Cost: Zero</strong>. The system found the indexes
    and raw data directly in <code class="western">/storage/ingest_cache/&lt;device&gt;</code>.</p></li>
</ol>

<h3 class="western">Scenario 2: Warm Tier Search (Charon / Multi-Cloud DropDir)</h3>
<p>Simulate that SLiCER pruned local data to save space
(Egress-Drop), but the data still exists in your mapped sync folder
(Google Drive, Dropbox, Vawlt.io, etc.).</p>
<ol>
    <li><p>Navigate to <code class="western">/storage/ingest_cache/&lt;device&gt;/data</code>
    and <code class="western">/storage/ingest_cache/&lt;device&gt;/index</code>.</p></li>
    <li><p><strong>Delete the closed block files and folders</strong>
    (<code class="western">.tar.xz</code> archives).</p></li>
    <li><p>Go to the Web Dashboard and run the query (or export) again.</p></li>
    <li><p>The system consults the <code class="western">/ledgers</code>,
    sees the blocks exist, attempts to fetch from <code class="western">ingest_cache</code>
    (fails), and falls back to <code class="western">/storage/tmp_charon</code>.</p></li>
    <li><p>The block is decrypted on demand. <strong>S3 Cost</strong>
    remains <strong>Zero</strong> since the transfer did not leave the
    local mapped drive.</p></li>
</ol>

<h3 class="western">Scenario 3: Cold Tier Search (AWS S3)</h3>
<p>Simulate very old data that has been wiped from both the local
cache and the local mapped cloud folder, surviving only in the AWS S3
bucket.</p>
<ol>
    <li><p>Navigate to <code class="western">/storage/tmp_charon/&lt;device&gt;</code>
    and <strong>delete everything</strong>.</p></li>
    <li><p>Ensure the <code class="western">/data</code> and <code class="western">/index</code>
    folders inside <code class="western">/ingest_cache</code> are also
    empty (as in Scenario 2).</p></li>
    <li><p>Go to the Web Dashboard and run the query (or export).</p></li>
    <li><p>SLiCER will try the cache (fail), try Charon (fail), and
    finally make a network call to AWS S3.</p></li>
    <li><p>The search will take slightly longer (download time), and the
    Web panel will update the <strong><code class="western">☁️ Custo S3</code></strong> indicator with the estimated egress cost in
    USD.</p></li>
</ol>

<p><em>Developed within the context of LASIGE / FCUL and UTFPR Campus
Pato Branco.</em></p>
</body>
</html>
