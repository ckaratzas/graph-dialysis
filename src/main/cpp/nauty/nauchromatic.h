/* Prototypes for the functions in nauchromatic.c */

#ifndef  _NAUCHROMATIC_H_    /* only process this file once */
#define  _NAUCHROMATIC_H_

#ifdef __cplusplus
extern "C" {
#endif

int chromaticnumber1(graph *g, int n, int minchi, int maxchi);
int chromaticnumber2(graph *g, int n, int minchi, int maxchi);
int chromaticnumber3(graph *g, int m, int n, int minchi, int maxchi);
int chromaticnumber(graph *g, int m, int n, int minchi, int maxchi);
int chromaticindex(graph *g, int m, int n, int *maxdeg);

#ifdef __cplusplus
}
#endif

#endif /* _NAUCHROMATIC_H_ */
