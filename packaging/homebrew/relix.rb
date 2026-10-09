# The relix formula. The release workflow of DarkCollective/relix-cli writes it from
# packaging/homebrew/relix.rb there, with the release's version and checksums: change it
# there, since a change made in the tap is overwritten by the next release.
class Relix < Formula
  desc "Run Relix relational-algebra scripts as a stage of a Unix pipeline"
  homepage "https://github.com/DarkCollective/relix-cli"
  version "@VERSION@"
  license "Apache-2.0"

  on_macos do
    on_arm do
      url "https://github.com/DarkCollective/relix-cli/releases/download/v#{version}/relix-#{version}-macos-aarch64.tar.gz"
      sha256 "@SHA256:macos-aarch64@"
    end
    on_intel do
      url "https://github.com/DarkCollective/relix-cli/releases/download/v#{version}/relix-#{version}-macos-x86_64.tar.gz"
      sha256 "@SHA256:macos-x86_64@"
    end
  end

  on_linux do
    on_arm do
      url "https://github.com/DarkCollective/relix-cli/releases/download/v#{version}/relix-#{version}-linux-aarch64.tar.gz"
      sha256 "@SHA256:linux-aarch64@"
    end
    on_intel do
      url "https://github.com/DarkCollective/relix-cli/releases/download/v#{version}/relix-#{version}-linux-x86_64.tar.gz"
      sha256 "@SHA256:linux-x86_64@"
    end
  end

  def install
    libexec.install Dir["*"]
    bin.install_symlink libexec/"bin/relix"
    man1.install Dir[libexec/"man/man1/*.1"]
    bash_completion.install libexec/"completions/relix.bash" => "relix"
    zsh_completion.install libexec/"completions/_relix"
    fish_completion.install libexec/"completions/relix.fish"
  end

  test do
    (testpath/"people.csv").write "id,name\n1,ada\n2,bob\n"
    output = pipe_output("#{bin}/relix -i People=csv:people.csv", "query { SELECT id > 1 (People) };\n")
    assert_equal "id\tname\n2\tbob\n", output
  end
end
