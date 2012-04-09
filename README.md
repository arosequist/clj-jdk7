# clj-jdk7

Wrappers for new functionality introduced in JDK7.

## Installation

clj-jdk7 on clojars

## Usage

See the [autodocs](http://arosequist.github.com/clj-jdk7/)

## Examples

Watch a folder for new, modified, and deleted files:

```clojure
(add-fs-watch
  #(println (.toString %1) %2)
  "/home/user/watch"
  [:create :modify :delete])
```

Create two temporary files, which will be deleted when the body completes:

```clojure
(create-temp-file [temp1 (create-temp-file)
                   temp2 (create-temp-file :suffix ".txt")]
  (println (.toString temp1))
  (println (.toString temp2)))
```

Same as above, but a shortcut for only one file:

```clojure
(create-temp-file temp
  (println (.toString temp)))
```

## License

Copyright (C) 2011 Anthony Rosequist

Distributed under the Eclipse Public License, the same as Clojure.
