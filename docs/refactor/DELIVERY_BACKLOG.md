# Delivery backlog overview

These are proposed planning keys, not live tracker items. The JSON contains code boundaries, dependency edges, tests and required evidence. Reconcile with existing D1A/D1B and release work before importing.

| ID | Gate | Owner responsibility | Depends on | Deliverable |
|---|---|---|---|---|
| TA-01 | G0 | Project owner + integration lead | — | Adopt the product charter and reconcile authority |
| TA-02 | G0 | Implementation lead | TA-01 | Obtain a current clean build and resolve workflow failures |
| TA-03 | G0 | Implementation lead | TA-01 | Freeze mapping regression fixtures and run identities |
| TA-04 | G0 | Research/evaluation owner | TA-03 | Make scoring fail closed on population and unit identity |
| TA-05 | G1 | Implementation lead | TA-03 | Implement checked RecallMapping records and codec preview |
| TA-06 | G1 | Implementation lead | TA-03, TA-05 | Standardize recall word identity and timing intake |
| TA-07 | G1 | Implementation lead | TA-05 | Separate raw measures, decoded choices, fill and assessed fidelity |
| TA-08 | G2 | Implementation lead | TA-05, TA-06, TA-07, TA-13 | Extract generic mapping logic from benchmark ownership |
| TA-09 | G2 | Implementation lead | TA-08 | Provide typed orchestration and explicit configuration |
| TA-10 | G2 | Implementation lead | TA-09 | Make batch execution and artifact publication safe |
| TA-11 | G2 | Implementation lead | TA-05, TA-06, TA-07, TA-09 | Deliver exchange tables and one independent R/Python consumer |
| TA-12 | G2 | Project owner + integration lead | TA-04, TA-10, TA-11 | Demonstrate the annotation-assisted preview end to end |
| TA-13 | G3 | Implementation lead | TA-03 | D1A S0: pin the text compiler, model, HSMM and view baseline |
| TA-14 | G3 | Project owner + integration lead | TA-01, TA-13 | D1A S1: record the approved ADR amendment |
| TA-15 | G3 | Implementation lead | TA-14 | D1A S2: complete checked core support and atlas substrate |
| TA-16 | G3 | Implementation lead | TA-15 | D1A S3: make acquisition support modality-capable |
| TA-17 | G3 | Implementation lead | TA-15 | D1A S4a: typed node support and fallible draft construction |
| TA-18 | G3 | Implementation lead | TA-16, TA-17 | D1A S4b: atlas envelope, identity joins and text witness |
| TA-19 | G3 | Implementation lead | TA-18 | D1A S4c: seal and split the alignment source contract |
| TA-20 | G4 | Implementation lead | TA-19 | D1A-film: compile an anchored film source |
| TA-21 | G4 | Implementation lead | TA-05, TA-08, TA-19 | D1B: carry typed support through source views, inference results and wire |
| TA-22 | G4 | Project owner + integration lead | TA-10, TA-11, TA-20, TA-21 | Prove the full compiled-film API journey |
| TA-23 | G5 | Implementation lead | TA-05, TA-07, TA-21 | Enforce evidence coverage and measure-kind honesty |
| TA-24 | G5 | Implementation lead | TA-06, TA-11, TA-21 | Implement word, hierarchy and time projection with explicit policies |
| TA-25 | G5 | Research/evaluation owner | TA-04, TA-07, TA-23 | Bind calibration and fidelity to their actual evidence |
| TA-26 | G5 | Research/evaluation owner | TA-23, TA-24, TA-25 | Verify analysis estimands with independent expected answers |
| TA-27 | G6 | Research/evaluation owner | TA-04, TA-12 | Freeze comparable evaluation tracks and baseline arms |
| TA-28 | G6 | Research/evaluation owner | TA-22, TA-23, TA-25, TA-27 | Freeze profiles and perform the permitted final evaluation |
| TA-29 | G6 | Implementation lead | TA-02, TA-12, TA-22, TA-26 | Package the public examples and release candidate |
| TA-30 | G6 | Project owner + integration lead | TA-01, TA-02, TA-28, TA-29 | Close release gates with evidence, not task counts |
