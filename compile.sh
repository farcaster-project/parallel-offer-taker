#!/usr/bin/env bash

"native-image" \
    -cp "$(clojure -Spath):classes" \
    -H:Name=parallel-offer-taker \
    -H:+ReportExceptionStackTraces \
    --initialize-at-build-time  \
    --enable-https \
    --verbose \
    --no-server \
    "-J-Xmx3g" \
    parallel_deal_taker.core
