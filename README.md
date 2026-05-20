# Jekyll site source

This branch holds the Jekyll source for the GitHub Pages site that publishes the [`himattm/skills`](https://github.com/himattm/skills) marketplace at https://himattm.github.io/skills/.

The skills themselves live on the [`main`](https://github.com/himattm/skills/tree/main) branch. At build time, the workflow on `main` overlays this branch's Jekyll files onto a checkout of `main` and runs `bin/build-posts.rb` followed by `jekyll build`, deploying the result to the `gh-pages` orphan branch.

Edit layouts, the Chirpy config, or `bin/build-posts.rb` here. Edit skills and plugin manifests on `main`.
