IMAGE_VERSION=0.1
IMAGE_NAME = seldonio/output_transformer

build:
	s2i build . seldonio/seldon-core-s2i-python3:0.2 $(IMAGE_NAME):$(IMAGE_VERSION)
