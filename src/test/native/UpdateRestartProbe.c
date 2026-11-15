#include <windows.h>
#include <stdio.h>
int WINAPI WinMain(HINSTANCE instance,HINSTANCE previous,LPSTR args,int show) {
    (void)instance;(void)previous;(void)args;(void)show;
    FILE *file=fopen("restarted.txt","wb");
    if(!file)return 1;
    fputs("restart-ok",file);fclose(file);return 0;
}
