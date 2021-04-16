# traktor-pro-metadata-handler
A little java tool that sniffes metadata from songs currently played in Traktor Pro and is able to distribute that data to multiple clients at once.


Main work is done in MetaDataCollector class. You can connect to any TraktorPro application running locally through their in-application "Broadcasting" feature. Just set up the same port as in the java class.
The meta data collector will "sniff" the tcp/ip connection and receive the music stream in an OGG Vorbis codec format. It will parse through the Vorbis header and extract the song title and artist and puts it out on console.
Note that the original purpose of the "Broadcasting" feature is completely negated hereby. It would be possible to implement a passthrough-function to stream to an iceCast Server, though.

The ConnectionHandler.java contains a connection handler that establishes an connection to the metadata collector and receives the meta data in realtime. It distributes this data to an arbitrary number of client threads, simultanousley.

This is work in progress.
