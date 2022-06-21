
Build with CUDA

```
bash configure --with-extra-cflags="-I/usr/local/cuda/include -fopenmp" --with-extra-cxxflags="-I/usr/local/cuda/include -fopenmp" --enable-debug
make CONF=debug -j
```
