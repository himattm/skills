source "https://rubygems.org"

gem "jekyll-theme-chirpy", "~> 7.2"

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
