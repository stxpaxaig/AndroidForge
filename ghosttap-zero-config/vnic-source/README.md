# Virtual Neural Intelligence Core (Python)

Standalone Python virtual-intelligence runtime: sparse virtual neural mesh, adaptive
synapses, persistent four-class memory, concept graph, cognition pipeline, learning
feedback, allow-listed tool execution, and runtime telemetry.

## Run
Python 3.10+, standard library only.

```bash
python vnic.py status
python vnic.py process "Investigate why a device is not connecting to Wi-Fi"
python vnic.py remember semantic "The device communicates over local Wi-Fi."
python vnic.py recall "local Wi-Fi"
python vnic.py learn 0.9
python vnic.py simulate --steps 100
```

Set `VNIC_DATA_DIR` to select persistent database location and `VNIC_SIM_UNITS`
to change the bounded materialized simulation population (default 4096).

## Architecture and honest scale representation
The logical network is configured as 8.7B virtual neurons and 87.4B virtual
synapses. The runtime materializes a bounded sparse working population rather
than allocating billions of Python objects. Its activation, propagation, synaptic
weight updates, memory writes/recalls, concept relations, feedback, and runtime
counters are executable.

The exact reference analytics are exposed as `reference_profile`. They preserve
the requested initial specification, including metrics that cannot be measured
on an ordinary host (for example biological-scale GB/s, physical watts, TB of
neural memory, and internal temperature). `runtime_observed` reports actual
measurements from this Python process separately. Do not present reference
specifications as measured hardware facts.

The cognition baseline is local symbolic/statistical processing, not an embedded
frontier LLM. For richer language reasoning or live research, connect a provider
through an explicit adapter. The tool registry is allow-listed and will not run
arbitrary generated shell commands or code.

## Main source
`vnic.py` contains the complete initial runtime and CLI in one inspectable file.
