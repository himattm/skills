#!/usr/bin/env ruby
# Build _posts/ from plugins/<plugin>/skills/<skill>/SKILL.md sources.
#
# Run from repo root before `jekyll build`. Each SKILL.md becomes a post with
# `categories: [<plugin>]` and a stable permalink at /plugins/<plugin>/skills/<skill>/.
# The transformed _posts/ directory is .gitignored so it only exists at build time.

require 'fileutils'

POSTS_DIR = '_posts'
PLUGINS_GLOB = 'plugins/*/skills/*/SKILL.md'

# Posts need a date to satisfy Jekyll, but skills aren't time-ordered. Use a
# fixed date (the marketplace's birth) so post order is alphabetical-ish via
# the filename suffix (plugin-skill).
POST_DATE = '2026-01-01'

FileUtils.rm_rf(POSTS_DIR)
FileUtils.mkdir_p(POSTS_DIR)

count = 0
Dir.glob(PLUGINS_GLOB).sort.each do |path|
  parts = path.split('/')
  # plugins/<plugin>/skills/<skill>/SKILL.md
  plugin = parts[1]
  skill = parts[3]

  raw = File.read(path)
  body = raw.sub(/\A---\s*\n.*?\n---\s*\n/m, '').sub(/\A\s*/, '')

  h1 = body.match(/^# (.+)$/)
  title = h1 ? h1[1].strip : skill

  filename = "#{POST_DATE}-#{plugin}-#{skill}.md"

  frontmatter = <<~YAML
    ---
    layout: post
    title: "#{title.gsub('"', '\\"')}"
    date: #{POST_DATE}
    categories: [#{plugin}]
    tags: [#{plugin}, skill]
    permalink: /plugins/#{plugin}/skills/#{skill}/
    toc: true
    pin: false
    ---

  YAML

  File.write(File.join(POSTS_DIR, filename), frontmatter + body)
  count += 1
end

puts "Generated #{count} posts in #{POSTS_DIR}/"
