#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include <jni.h>
#include <android/log.h>

#define  LOG_TAG    "libmemscan"
#define  LOGI(...)  __android_log_print(ANDROID_LOG_INFO,LOG_TAG,__VA_ARGS__)
#define  LOGE(...)  __android_log_print(ANDROID_LOG_ERROR,LOG_TAG,__VA_ARGS__)

// From:
// http://stackoverflow.com/questions/230689/best-way-to-throw-exceptions-in-jni-code/240506#240506
jint
throwOutOfMemoryError( JNIEnv *env, char *message )
{
	jclass		 exClass;
	char		*className = "java/lang/OutOfMemoryError" ;

	exClass = (*env)->FindClass( env, className );
	if ( exClass == NULL )
		{
//		return throwNoClassDefError( env, className );
		}

	return (*env)->ThrowNew( env, exClass, message );
}

volatile int gStopNow;

void
Java_com_example_memscan_MemScan_stopMemScan( JNIEnv* env, jobject thiz )
{
	gStopNow = 1;
}

jstring
Java_com_example_memscan_MemScan_memScan( JNIEnv* env, jobject thiz, jint bytes )
{
	char buf[100];	// Temporary string buffer

	gStopNow = 0;
	jstring result = NULL;

	int outputBufSize = 10 * 1024;	// Only return 10K of error text
	char* outputBuf = malloc(outputBufSize);
	if (outputBuf == NULL)
	{
		throwOutOfMemoryError(env, "Out of memory allocating outputBuf");
		return NULL;
	}
	outputBuf[0] = '\0';

	int* pMem = malloc(bytes);
	if (pMem == NULL)
	{
		free(outputBuf);
		snprintf(buf, sizeof(buf), "Out of memory allocating large block of %d bytes", bytes);
		throwOutOfMemoryError(env, buf);
		return NULL;
	}

	LOGI("Testing %d bytes of memory", bytes);

	int		count = bytes / sizeof(int);	// round down
	int		i = 0;

	// Fill memory with known value
	memset(pMem, 0, bytes);

	int iter;
	for (iter = 0; ; iter++)
	{
		if (gStopNow) {
			LOGI( "Stopping scanning passes" );
			break;
		}
		LOGI( "Scan pass %d...", iter );

		//read
		for ( i = 0; i < count; i++ ) {
			int memRead = pMem[ i ];
			int memExpected = 0;

			if ( memExpected != memRead ) {
				if (outputBuf[0] == '\0') {
					strlcat(outputBuf, "***** MEMORY CORRUPTION DETECTED ******\n", outputBufSize);
					strlcat(outputBuf, "---------------------------------------\n", outputBufSize);
					strlcat(outputBuf, "Address      Found        Expected     \n", outputBufSize);
					strlcat(outputBuf, "---------------------------------------\n", outputBufSize);
					LOGI("%s", outputBuf);
				}
				snprintf(buf, sizeof(buf), "0x%08X   0x%08X   0x%08X\n",
						(unsigned int)&pMem[i], memRead, memExpected);
				LOGI("%s", buf);

				// Append to outputBuf
				if (strlcat(outputBuf, buf, outputBufSize) >= outputBufSize) {
					char truncationWarning[] = "...<output truncated>";
					// if we can fit at least 100 real chars plus the warning
					if (outputBufSize > 100 + sizeof(truncationWarning)) {
						// Terminate the buf so we can append the warning
						outputBuf[outputBufSize - sizeof(truncationWarning)] = '\0';
						// Append truncation warning
						strlcat(outputBuf, truncationWarning, outputBufSize);
						break;	// stop trying to append more
					}
				}
			}
		}

		// If we found something, break so we can return
		if (outputBuf[0] != '\0') {
			break;
		}
	}

	result = (*env)->NewStringUTF(env, outputBuf);
	free(pMem);
	free(outputBuf);
	return result;
}
