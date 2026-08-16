/* Prototypes for the functions in nauconnect.c */

#ifndef  _NAUCONNECT_H_    /* only process this file once */
#define  _NAUCONNECT_H_

#ifdef __cplusplus
extern "C" {
#endif

int maxvertexflow1(graph*,int,int,int,int,boolean);
int maxvertexflow(graph*,graph*,set*,int*,int*,int,int,int,int,int,boolean);
int connectivity(graph*,int,int,boolean);
boolean isthisconnected(graph*,int,int,int,boolean);
int maxedgeflow1(graph*,int,int,int,int);
int maxedgeflow(graph*,graph*,int,int,int,int,set*,int*,int*,int);
int edgeconnectivity(graph*,int,int);
boolean isthisedgeconnected(graph*,int,int,int);

#ifdef __cplusplus
}
#endif

#endif
