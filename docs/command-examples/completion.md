## Examples

The release archives carry each shell's script in `completions/`, and Homebrew installs
them. To load one by hand, add the line for your shell to its startup file:

```text
bash        source <(relix completion bash)
zsh         source <(relix completion zsh)
fish        relix completion fish > ~/.config/fish/completions/relix.fish
PowerShell  relix completion powershell | Out-String | Invoke-Expression
```

The zsh script is also a completion function, so it can be saved as `_relix` in a
directory on `$fpath` instead.
