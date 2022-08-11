# parallel offer taker

launches multiple instances of farcaster to take offers from farcaster.dev.
a lot of functionality is not exposed yet, but quite trivial to do - just open up issues/PRs.

## configuration
use a configuration `.edn` file to configure:

- `:address-btc` is the equivalent of the `--btc-addr` option for `swap-cli make` and `swap-cli take`
- `:address-xmr` is the equivalent of the `--xmr-addr` option for `swap-cli make` and `swap-cli take`
- `:data-dir-root` points to the directory where your `.data_dir_$SWAP_INDEX` dirs will live
- `:farcaster-binaries-path` points to the directory containing `farcasterd` and `swap-cli`
- `:farcaster-config-toml-file` points to the `.toml` file containing your configuration

A sample configuration file can be found [here](./config-sample.edn). The config file's location is passed to `parallel-offer-taker` with the `--config` flag, else assumed to be `./config.edn`.

## usage
- `clj -M:native-image` or download binary from the latest action build from https://github.com/farcaster-project/parallel-offer-taker/actions/workflows/native-image.yaml?query=branch%3Amain to the directory containing `config.edn`
- recommendation: first test whether your farcasterd works manually with the `:farcaster-config-toml-file`.
- launch with `./parallel-offer-taker $SWAP_START_INDEX_INCLUSIVE $SWAP_END_INDEX_EXCLUSIVE`. 
  i.e. `./parallel-offer-taker 0 50` would launch 50 swaps, with their respective `.data_dir_$SWAP_INDEX` enumerated from 0 - 49. currently, these directories must be created ahead of time - the binary won't do it for you.
- any farcasterd instance spawned that finishes all its running swaps will be killed automatically.
- if you kill `parallel-offer-taker`, all swaps it spawned will die too.
- if you relaunch `parallel-offer-taker` and any swaps in the range `$SWAP_START_INDEX_INCLUSIVE..$SWAP_END_INDEX_EXCLUSIVE` were left unfinished before, `parallel-offer-taker` will automatically restore their latest checkpoint

