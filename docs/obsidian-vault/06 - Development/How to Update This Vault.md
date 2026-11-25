---
tags: [meta]
---
# How to Update This Vault

1. **The code is the source of truth.** When behaviour changes, update the feature note's
   *How it works*, *Relevant files* and *Known limitations* in the same commit.
2. Add an entry to [[Change Log]] (newest first): what changed, why, and measured numbers if any.
3. Bugs: add symptom → cause → fix to [[Fixed Issues]]; open problems go to [[Known Issues]].
4. New feature: copy the headings of an existing feature note (what it does, how it works, files,
   dependencies, storage, performance, interactions, limitations, future, related notes) and link
   it from [[00 - Home]].
5. Use tags `#feature`, `#architecture`, `#performance`, `#bug`, `#design`, `#development`.
6. Don't describe things the code doesn't do. "Not tested on hardware" is a fine thing to write.
