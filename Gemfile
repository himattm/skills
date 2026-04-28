source "https://rubygems.org"

gem "jekyll-theme-chirpy", "~> 7.2"

# Plugins listed in _config.yml. They're transitive deps of Chirpy, but Ruby 3.3+
# refuses to require gems not in the Gemfile, so they need to be declared here too.
group :jekyll_plugins do
  gem "jekyll-paginate", "~> 1.1"
  gem "jekyll-redirect-from", "~> 0.16"
  gem "jekyll-seo-tag", "~> 2.8"
  gem "jekyll-archives", "~> 2.3"
  gem "jekyll-sitemap", "~> 1.4"
  gem "jekyll-include-cache", "~> 0.2"
end

group :test do
  gem "html-proofer", "~> 5.0"
end

# Windows + JRuby don't bundle zoneinfo; this keeps local dev portable.
platforms :mingw, :x64_mingw, :mswin, :jruby do
  gem "tzinfo", ">= 1", "< 3"
  gem "tzinfo-data"
end

gem "wdm", "~> 0.2.0", :platforms => [:mingw, :x64_mingw, :mswin]

# Ruby 3+ no longer bundles webrick; Jekyll's `serve` needs it.
gem "webrick"
