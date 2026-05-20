#!/usr/bin/env ruby
# Build _posts/ from <plugin>/skills/<skill>/SKILL.md sources.
#
# The build workflow runs from a checkout of `main` (which has the three
# top-level plugin dirs `android/`, `review/`, `utilities/`) with this
# branch's Jekyll source overlaid on top. SKILL.md files are discovered
# from those plugin dirs.
#
# Each SKILL.md becomes a Chirpy post with:
#   - title: derived from the first markdown H1 in the body, ignoring lines
#     inside fenced code blocks (so `# Wrong — ...` shell comments don't win).
#     Falls back to the directory slug if no H1 is found.
#   - date:  the date of the first git commit that introduced the file (with
#     --follow so renames don't reset history). Falls back to today.
#   - permalink: /<plugin>/skills/<skill>/ — mirrors source structure.
#   - categories: [<plugin>] — drives Chirpy's auto-archive pages.
#
# The transformed _posts/ directory is .gitignored so it only exists at build
# time. Run from repo root before `jekyll build`.

require 'fileutils'
require 'open3'

POSTS_DIR = '_posts'
SKILLS_GLOB = '{android,review,utilities}/skills/*/SKILL.md'

def first_h1_outside_code(body)
  clean = body.gsub(/```[\s\S]*?```/, '')
  m = clean.match(/^# (.+)$/)
  m ? m[1].strip : nil
end

def first_commit_date(path)
  # --follow tracks the file across renames. We can't combine it with --reverse
  # (a known git quirk where --reverse runs before --follow's rename filter),
  # so take the last line of default-order (newest-first) output.
  out, status = Open3.capture2('git', 'log', '--follow', '--format=%cI', '--', path)
  return nil unless status.success?
  iso = out.strip.split("\n").last
  iso&.split('T')&.first
end

FileUtils.rm_rf(POSTS_DIR)
FileUtils.mkdir_p(POSTS_DIR)

count = 0
Dir.glob(SKILLS_GLOB).sort.each do |path|
  parts = path.split('/')
  plugin = parts[0]
  skill = parts[2]

  raw = File.read(path)
  body = raw.sub(/\A---\s*\n.*?\n---\s*\n/m, '').sub(/\A\s*/, '')

  title = first_h1_outside_code(body) || skill
  date = first_commit_date(path) || Time.now.strftime('%Y-%m-%d')

  filename = "#{date}-#{plugin}-#{skill}.md"

  frontmatter = <<~YAML
    ---
    layout: post
    title: "#{title.gsub('"', '\\"')}"
    date: #{date}
    categories: [#{plugin}]
    tags: [#{plugin}, skill]
    permalink: /#{plugin}/skills/#{skill}/
    toc: true
    pin: false
    ---

  YAML

  File.write(File.join(POSTS_DIR, filename), frontmatter + body)
  count += 1
end

puts "Generated #{count} posts in #{POSTS_DIR}/"
