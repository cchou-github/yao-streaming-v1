# Public ALB, provisioned entirely in Terraform rather than via the AWS
# Load Balancer Controller + a Kubernetes Ingress. The latter is the more
# "Kubernetes-native" path, but its AWS resources live outside Terraform's
# state entirely - risking terraform destroy failing or silently leaving
# an orphaned, still-billing ALB behind. Every other piece of this project
# (S3 force_destroy, ECR force_delete, the transcode pipeline's internal
# ALB) has been deliberately built so destroy always cleanly tears
# everything down; this keeps that guarantee.
#
# HTTP only, no custom domain/TLS - those need a real domain + Route53 +
# ACM, none of which exist yet. Plain port 80 for this pass.

resource "aws_security_group" "alb" {
  name        = "${var.project_name}-alb-sg"
  description = "Public ALB - allows inbound HTTP from anywhere"
  vpc_id      = aws_vpc.main.id

  ingress {
    description = "HTTP from anywhere"
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "${var.project_name}-alb-sg"
  }
}

# Nothing currently allows inbound traffic to the node's NodePort - this is
# the rule that makes the ALB -> node -> pod path actually work. Attached
# to the cluster's own security group, the same one rds.tf's security
# group already references for the reverse direction (RDS allowing EKS in).
resource "aws_security_group_rule" "alb_to_nodes" {
  type                     = "ingress"
  from_port                = 30080
  to_port                  = 30080
  protocol                 = "tcp"
  security_group_id        = aws_eks_cluster.main.vpc_config[0].cluster_security_group_id
  source_security_group_id = aws_security_group.alb.id
  description              = "NodePort from the public ALB"
}

resource "aws_lb" "app" {
  name               = "${var.project_name}-alb"
  internal           = false
  load_balancer_type = "application"
  security_groups    = [aws_security_group.alb.id]
  subnets            = aws_subnet.public[*].id

  tags = {
    Name = "${var.project_name}-alb"
  }
}

resource "aws_lb_target_group" "app" {
  name     = "${var.project_name}-app-tg"
  port     = 30080
  protocol = "HTTP"
  vpc_id   = aws_vpc.main.id
  # instance, not ip: targets are EKS worker nodes (via NodePort), not pod
  # IPs directly - the same target type the transcode pipeline's internal
  # ALB uses for the same reason.
  target_type = "instance"

  health_check {
    path                = "/actuator/health"
    port                = "traffic-port"
    healthy_threshold   = 2
    unhealthy_threshold = 3
    interval            = 15
    timeout             = 5
    matcher             = "200"
  }

  tags = {
    Name = "${var.project_name}-app-tg"
  }
}

resource "aws_lb_listener" "app_http" {
  load_balancer_arn = aws_lb.app.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.app.arn
  }
}

# /internal/** (TranscodeCallbackController) is only meant to be reachable
# via the internal ALB - SecurityConfig permits it with no session/CSRF
# check at all, trusting the network path as the real gate (see
# internal-alb.tf). But this public ALB and the internal one forward to
# the app on the exact same NodePort/pods, so without this rule anyone on
# the internet could hit /internal/** straight through this ALB too,
# defeating that gate entirely. Evaluated before the default forward
# action, so a matching request never reaches the target group at all.
resource "aws_lb_listener_rule" "block_internal" {
  listener_arn = aws_lb_listener.app_http.arn
  priority     = 1

  action {
    type = "fixed-response"

    fixed_response {
      content_type = "text/plain"
      message_body = "Not Found"
      status_code  = "404"
    }
  }

  condition {
    path_pattern {
      values = ["/internal/*"]
    }
  }
}

# Keeps the target group's registered targets correct as the node group
# scales, without needing the AWS Load Balancer Controller - same
# mechanism the transcode pipeline's internal ALB already uses.
resource "aws_autoscaling_attachment" "app" {
  autoscaling_group_name = aws_eks_node_group.main.resources[0].autoscaling_groups[0].name
  lb_target_group_arn    = aws_lb_target_group.app.arn
}
