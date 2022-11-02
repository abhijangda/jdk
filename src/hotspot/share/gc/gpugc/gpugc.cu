#include <stdio.h>

__global__ void cuda_hello(){
    printf("Hello World from GPU!\n");
}

int gpugc() {
    cuda_hello<<<1,1>>>(); 
    return 0;
}
