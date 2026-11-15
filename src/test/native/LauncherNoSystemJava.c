#define UNICODE
#define _UNICODE
#include <windows.h>
/* Isolate discovery for the missing-Java branch; production source remains unchanged. */
static LSTATUS WINAPI test_no_registry(HKEY h,LPCWSTR p,DWORD o,REGSAM a,PHKEY k){(void)h;(void)p;(void)o;(void)a;(void)k;return ERROR_FILE_NOT_FOUND;}
static DWORD WINAPI test_no_path(LPCWSTR p,LPCWSTR f,LPCWSTR e,DWORD n,LPWSTR b,LPWSTR *part){(void)p;(void)f;(void)e;(void)n;(void)b;(void)part;return 0;}
#define RegOpenKeyExW test_no_registry
#define SearchPathW test_no_path
#include "../../main/native/ttwr-sqlite.c"
