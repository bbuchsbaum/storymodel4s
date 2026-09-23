#!/usr/bin/env bash
set -eu
text_source_court=$(mktemp -d /private/tmp/p1-readers-witness.XXXXXX)
trap 'rm -rf "$text_source_court"' EXIT
env -u STORYMODEL4S_GRAKERN_BUILD sbt -batch -Dstorymodel4s.grakern.build=/private/tmp/storymodel-p1-grakern-0329c43c "pipeline/Test/runMain storymodel4s.pipeline.TextSourceExchangeWitness $text_source_court/witness"
python3 tools/check_text_source_exchange.py --suite "$text_source_court/witness"
python3 examples/storymodel-export/check_export.py examples/storymodel-export/war-of-the-ghosts
