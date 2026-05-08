# This is the default recipe when no arguments are provided
[private]
default:
  @just --list --unsorted

# Compile everything
make-patch:
  git diff -p 11.5 HEAD > android.patch
