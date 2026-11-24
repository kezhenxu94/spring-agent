Read a text file. Lines come back numbered from 1, up to 2000 lines by default; give `offset` and
`limit` for a slice of a long file. Lines over 2000 characters are truncated. Binary files are not
decoded, and a directory cannot be read.
