# Deferred: demand-adaptive regional sampling

Not part of v1. Keep fixed major-city coverage and three-hour cadence until usage justifies complexity.

Potential signals: unique regional forecast views (dedup installation/account + region + hour), saved places, enabled event alerts, actual map viewport demand. Never count repeated refreshes as independent users. Cap per-installation region activations. Store aggregate region demand rather than raw movement histories.

Separate demand from urgency: proximity to the target solar event, input age and upstream model availability. A remote location with one enabled imminent alert can outrank a populous city whose event is far away. Supporting cloud-field nodes inherit dependent regions' priority and are deduplicated globally.

Candidate lifecycle: immediate activation, 24-hour active window, gradual decay over 72 hours; tune from observed usage. Reserve a minimum refresh floor for low-demand regions and enforce a global weighted-call budget before dispatch. Growth must not turn every new user coordinate into an unbounded collector.

Candidate spatial evolution: stable hierarchical cells; validate coarse/fine accuracy in coastal and mountain areas; archive raw weather fields to recompute location-specific historical scores. Treat data-resolution/algorithm changes as separate series versions. Never concatenate different representative locations or algorithms into one apparent weather-change curve.

Revisit when measured traffic, cache misses and collector failure rates are available. Preserve the fixed city catalogue as bootstrap coverage; change policies gradually with explicit budget simulations and coverage tests.
