set -eu
bash tools/reference-scope.sh 604080fc5628499efbd9f54de375c0d626a4fa68 HEAD > /private/tmp/p1-repaired-reference-scope.log
env -u STORYMODEL4S_GRAKERN_BUILD sbt -batch -Dstorymodel4s.grakern.build=/private/tmp/storymodel-p1-grakern-0329c43c 'set ThisBuild / tlFatalWarnings := true' checkAll
