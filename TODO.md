# Nexus Tree Tools - TODO

## 2026-05-15

* trasformare 1 come 0 e 2 come 1
  - 1.1.1.1 --> 0.0.0.0.0
* valutare se aggiungere un 1 finale per non avere due nodi con lo stesso hier_label16 o 32

## 2026-05-14

albero polifiletico. Trasforemare i rami polifiletici nel JSON. in questo modo:
Sia N un nodo con hier_label 1.1.1.X, dove X è un numero diverso da 1 oppure 2. Si prende il suo padre P, con hier_label 1.1.1. Si crea un nuovo nodo N', con hier_label
