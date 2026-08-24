# Internal-only ALB for the transcode completion callback
# (TranscodeCallbackController, /internal/transcode/callback) - mirrors
# alb.tf's public ALB closely, but internal = true, private subnets, and
# its security group is scoped to the completion Lambda's own SG rather
# than 0.0.0.0/0. The network path IS the authorization here (see
# SecurityConfig's comment on the permitAll() for /internal/**) - there is
# no app-level auth on the other end, so this SG is the actual gate.
#
# Needs its own target group, same NodePort/health check as alb.tf's -
# a target group can only ever be attached to one load balancer at a
# time (a real AWS API constraint, not a Terraform choice: reusing
# aws_lb_target_group.app here fails at apply time with
# "TargetGroupAssociationLimit"). Same target instances either way, since
# both target groups point at the same node group's ASG.

resource "aws_security_group" "alb_internal" {
  name        = "${var.project_name}-alb-internal-sg"
  description = "Internal ALB for the transcode completion callback - completion Lambda only"
  vpc_id      = aws_vpc.main.id

  ingress {
    description     = "HTTP from the completion Lambda only"
    from_port       = 80
    to_port         = 80
    protocol        = "tcp"
    security_groups = [aws_security_group.lambda_complete.id]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "${var.project_name}-alb-internal-sg"
  }
}

# Same purpose as alb_to_nodes in alb.tf, for the internal ALB's own SG.
resource "aws_security_group_rule" "alb_internal_to_nodes" {
  type                     = "ingress"
  from_port                = 30080
  to_port                  = 30080
  protocol                 = "tcp"
  security_group_id        = aws_eks_cluster.main.vpc_config[0].cluster_security_group_id
  source_security_group_id = aws_security_group.alb_internal.id
  description              = "NodePort from the internal ALB"
}

resource "aws_lb" "app_internal" {
  name               = "${var.project_name}-alb-internal"
  internal           = true
  load_balancer_type = "application"
  security_groups    = [aws_security_group.alb_internal.id]
  subnets            = aws_subnet.private[*].id

  tags = {
    Name = "${var.project_name}-alb-internal"
  }
}

resource "aws_lb_target_group" "app_internal" {
  name        = "${var.project_name}-app-internal-tg"
  port        = 30080
  protocol    = "HTTP"
  vpc_id      = aws_vpc.main.id
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
    Name = "${var.project_name}-app-internal-tg"
  }
}

resource "aws_lb_listener" "app_internal_http" {
  load_balancer_arn = aws_lb.app_internal.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.app_internal.arn
  }
}

# Same mechanism as aws_autoscaling_attachment.app in alb.tf, registering
# this second target group against the same node group's ASG.
resource "aws_autoscaling_attachment" "app_internal" {
  autoscaling_group_name = aws_eks_node_group.main.resources[0].autoscaling_groups[0].name
  lb_target_group_arn    = aws_lb_target_group.app_internal.arn
}
